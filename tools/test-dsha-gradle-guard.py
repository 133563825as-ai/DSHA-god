#!/usr/bin/env python3
"""纯Python synthetic负控；不执行JVM/Gradle，不声明真实probe通过。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('guard',ROOT/'tools/dsha_gradle_guard.py')
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)


class GuardTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='guard-synthetic-')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)

    def test_every_project_or_extra_argument_rejects_before_probe(self):
        for args in [[],[':app:testStandardDebugUnitTest'],[':app:compileStandardDebugJavaWithJavac'],
                     [':app:assembleStandardRelease'],['--stop'],['--daemon'],
                     ['--preflight','--init-script','evil'],['--preflight','-Pkey=value']]:
            with self.subTest(args=args), patch.object(g,'exact_inputs') as probe, patch.object(g,'GlobalLock') as lock, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2,g.main(args));probe.assert_not_called();lock.assert_not_called()

    def test_status_never_creates_or_cleans(self):
        target=self.root/'lock'
        self.assertEqual('absent',g.status(target)['lock']);self.assertFalse(target.exists())
        target.symlink_to(self.root/'missing')
        self.assertTrue(g.status(target)['symlink']);self.assertTrue(target.is_symlink())

    def test_owned_lock_releases_only_exact_owner(self):
        target=self.root/'lock'
        with g.GlobalLock(target):
            self.assertTrue((target/'owner.json').is_file())
            owner=json.loads((target/'owner.json').read_text())
            self.assertEqual(os.getpid(),owner['pid']);self.assertIn('boot',owner);self.assertIn('birth',owner)
        self.assertFalse(target.exists())

    def test_existing_and_symlink_lock_never_autoclean(self):
        for kind in ['directory','symlink']:
            target=self.root/kind
            target.mkdir() if kind=='directory' else target.symlink_to(self.root/'missing')
            with self.assertRaisesRegex(g.Refused,'GLOBAL_LOCK_EXISTS'):
                with g.GlobalLock(target):self.fail()
            self.assertTrue(os.path.lexists(target))

    def test_changed_owner_retained(self):
        target=self.root/'changed'
        with self.assertRaisesRegex(g.Refused,'GLOBAL_LOCK_CHANGED_RETAINED'):
            with g.GlobalLock(target):
                (target/'owner.json').write_text('foreign')
        self.assertEqual('foreign',(target/'owner.json').read_text())

    def test_unknown_extra_lock_file_not_recursively_removed(self):
        target=self.root/'extra'
        with self.assertRaises(OSError):
            with g.GlobalLock(target):(target/'foreign').write_text('keep')
        self.assertEqual('keep',(target/'foreign').read_text())

    def test_environment_injection_never_echoes_values(self):
        for key in [*g.INJECT,'ORG_GRADLE_PROJECT_secret']:
            with self.subTest(key=key), self.assertRaises(g.Refused) as failure:
                g.injection({key:'synthetic-secret'},self.root,self.root)
            self.assertNotIn('synthetic-secret',str(failure.exception))

    def test_implicit_init_and_symlink_reject(self):
        for name in ['init.gradle','init.gradle.kts']:
            path=self.root/name;path.write_text('synthetic')
            with self.assertRaisesRegex(g.Refused,'IMPLICIT_INIT'):g.injection({},self.root,self.root)
            path.unlink()
        (self.root/'init.d').symlink_to(self.root/'missing')
        with self.assertRaisesRegex(g.Refused,'IMPLICIT_INIT'):g.injection({},self.root,self.root)

    def test_distribution_init_rejects_any_script(self):
        dist=self.root/'dist';(dist/'init.d').mkdir(parents=True)
        (dist/'init.d/readme.txt').write_text('doc')
        g.injection({},self.root/'cache',self.root/'home',dist)
        (dist/'init.d/x.gradle').write_text('script')
        with self.assertRaisesRegex(g.Refused,'DISTRIBUTION_INIT'):g.injection({},self.root/'cache',self.root/'home',dist)

    def test_missing_zip_rejects_without_version_or_network_fallback(self):
        (self.root/'gradle/wrapper').mkdir(parents=True);(self.root/'ci').mkdir()
        (self.root/'gradle/wrapper/gradle-wrapper.properties').write_text('distributionSha256Sum='+'a'*64)
        (self.root/'ci/toolchain.lock.json').write_text(json.dumps({'gradle':'9.3.1','java':'17','androidPlatform':'android-37.0'}))
        cache=self.root/'cache';(cache/'wrapper/dists/gradle-9.3.1-bin/a/gradle-9.3.1').mkdir(parents=True)
        with self.assertRaisesRegex(g.Refused,'DISTRIBUTION_ZIP_PIN_MISSING'):
            g.exact_inputs(self.root,cache,self.root/'sdk',self.root/'jdk')

    def test_even_all_static_conditions_never_admit_jvm_probes(self):
        with patch.object(g,'ROOT',self.root), patch.object(g,'GlobalLock'), patch.object(g,'injection'), \
             patch.object(g,'memory_available',return_value=g.REQUIRED), \
             patch.object(g,'exact_inputs',return_value={'distribution':str(self.root)}), \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1,g.main(['--preflight']))
        receipt=json.loads(next((self.root/'app/build/rebirth-rc1-closure/gradle-guard').glob('*/receipt.json')).read_text())
        self.assertEqual('DIAGNOSTIC_JVM_PROBES_NOT_ADMITTED',receipt['reason'])
        self.assertFalse(receipt['probeExecuted'])
        self.assertEqual('NOT_ADMITTED',receipt['projectExecution'])


if __name__=='__main__':unittest.main(verbosity=2)
