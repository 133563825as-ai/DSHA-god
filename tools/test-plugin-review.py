#!/usr/bin/env python3
"""验证直接启用、旧标记升级及加载失败后的插件状态。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tarfile
import time
import unittest
import uuid
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]


class ReviewTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='dsha-review-');self.root=Path(self.temporary.name)
        self.environment=patch.dict(os.environ,{'DSHA_TEST_ROOT':str(self.root),'DSH_HOME':'/root/.dsh','DSHA_PLUGIN_TASK':'1'*32});self.environment.start()
        spec=importlib.util.spec_from_file_location('plugin_review_fixture',ROOT/'app/src/main/assets/plugin-manager.py')
        self.manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.manager);self.life=self.manager.lifecycle()
        self.package=self.root/'root/.dsh/profiles/web/node_modules/test-plugin';self.package.mkdir(parents=True)
        self.put(self.package/'package.json',{'name':'test-plugin','version':'1.0.0','dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        self.put(self.package/'cordis.patch.yml','[]\n');self.put(self.package/'index.js',"throw Error('metadata inspection must never execute this');")
        self.manifest=self.package.parents[1]/'package.json'
        self.put(self.manifest,{'dependencies':{'test-plugin':'^1.0.0'},'dsh':{'profile':{'bundles':[]}}})
        runtime=self.root/'usr/local/lib/node_modules/@deepseek-ai/dsh'
        self.put(runtime/'package.json',{'name':'@deepseek-ai/dsh','version':'0.2.2-rc.1'})
        boot=runtime/'node_modules/@deepseek-ai/dsh-app-boot'
        self.put(boot/'package.json',{'name':'@deepseek-ai/dsh-app-boot','version':'0.2.2-rc.1','main':'lib/index.js'})
        self.put(boot/'lib/index.js',"throw Error('compatibility must never import app-boot');")


    def tearDown(self):
        self.environment.stop();self.temporary.cleanup()

    def put(self,path,value):
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value) if isinstance(value,dict) else value,encoding='utf8')

    def preview(self):
        with patch.object(self.manager,'result') as result:
            self.life.review_existing('test-plugin')
            return result.call_args.kwargs['preview']

    def enabled(self):
        return 'test-plugin' in json.loads(self.manifest.read_text())['dsh']['profile']['bundles']

    def enable(self):
        fingerprint=self.manager.dependencies().current(str(self.package))['sha256']
        self.life.queue_activation('test-plugin',fingerprint,'1.0.0')
        with contextlib.redirect_stdout(io.StringIO()):self.assertEqual(0,self.manager.builtin.enable_plugin('test-plugin'))
        self.assertTrue(self.enabled())

    def test_existing_plugin_enables_without_approval_file(self):
        preview=self.preview();self.assertEqual('legacy-unknown',preview['items'][0]['dependencyState'])
        self.assertFalse(self.enabled())
        with contextlib.redirect_stdout(io.StringIO()):self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        self.assertTrue(self.enabled());self.assertFalse(Path(self.manager.task_file('.approval')).exists())
        self.assertEqual('queued',self.life.activation_state()['entries']['test-plugin']['status'])

    def test_changed_source_after_prepare_does_not_enable(self):
        preview=self.preview();self.put(self.package/'index.js','new source after prepare')
        with self.assertRaisesRegex(ValueError,'准备后发生变化'):
            self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        self.assertFalse(self.enabled());self.assertEqual('new source after prepare',(self.package/'index.js').read_text())

    def test_upgrade_enables_only_exact_legacy_review_marker(self):
        document=json.loads(self.manifest.read_text())
        for name,marker in (('legacy-plugin','DSHA_REVIEW_REQUIRED\n'),('user-disabled','')):
            directory=self.root/'root/.dsh/plugin-src'/name
            self.put(directory/'package.json',{'name':name,'version':'1.0.0','dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
            self.put(directory/'cordis.patch.yml','[]\n')
            self.put(directory/'index.js','export {};')
            marker_path=self.root/'root/.dsh/profiles/web/node_modules'/(name+'.disabled')
            marker_path.parent.mkdir(parents=True,exist_ok=True);marker_path.write_bytes(marker.encode())
            document['dependencies'][name]='link:/root/.dsh/plugin-src/'+name
        self.put(self.manifest,document)
        self.assertEqual(b'DSHA_REVIEW_REQUIRED\n',(self.root/'root/.dsh/profiles/web/node_modules/legacy-plugin.disabled').read_bytes())
        self.assertEqual('link:/root/.dsh/plugin-src/legacy-plugin',self.manager.builtin.read_manifest()['dependencies']['legacy-plugin'])
        legacy=self.root/'root/.dsh/plugin-src/legacy-plugin'
        self.assertEqual('legacy-plugin',self.manager.plugin_package(str(legacy))['name'])
        self.assertEqual([],self.manager.dependencies().current(str(legacy))['missing'])
        link=patch.object(self.manager.os,'symlink',side_effect=lambda src,dst,**_:shutil.copytree((Path(dst).parent/src).resolve(),dst)) if os.name=='nt' else contextlib.nullcontext()
        with link,patch.object(self.manager,'result') as outcome,contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,self.life.migrate_legacy_reviews())
        self.assertEqual(['legacy-plugin'],outcome.call_args.kwargs['activated'],str(outcome.call_args))
        self.assertFalse((self.root/'root/.dsh/profiles/web/node_modules/legacy-plugin.disabled').exists())
        self.assertTrue((self.root/'root/.dsh/profiles/web/node_modules/user-disabled.disabled').exists())
        bundles=json.loads(self.manifest.read_text())['dsh']['profile']['bundles']
        self.assertIn('legacy-plugin',bundles);self.assertNotIn('user-disabled',bundles)

    def test_unattributed_failure_preserves_enabled_intent_until_new_startup(self):
        self.enable();first=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',first)
            with patch.object(self.manager,'result') as result:
                self.life.loading('failed',first)
        entry=self.life.activation_state()['entries']['test-plugin']
        self.assertTrue(self.enabled());self.assertEqual('unknown',entry['status'])
        self.assertEqual('STARTUP_FAILURE_UNATTRIBUTED',entry['reason'])
        self.assertEqual('NO_HOST_BINDING',entry['evidenceReason']);self.assertEqual('CONTENT_UNVERIFIED',entry['contentReason'])
        self.assertEqual('unattributed',entry['attribution']);self.assertNotIn('loadedAt',entry)
        self.assertEqual([],result.call_args.kwargs['failedPlugins'])
        self.assertEqual('unknown',result.call_args.kwargs['loadOutcome'])
        self.assertEqual(['test-plugin'],[row['name'] for row in result.call_args.kwargs['unattributedPlugins']])
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('complete',first);self.life.loading('begin',first)
        self.assertEqual(entry,self.life.activation_state()['entries']['test-plugin'])
        second=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):self.life.loading('begin',second)
        current=self.life.activation_state()['entries']['test-plugin']
        self.assertTrue(self.enabled());self.assertEqual('attempted',current['status']);self.assertEqual(second,current['startup'])
        for field in ('reason','evidenceReason','contentReason','attribution','observedAt','loadedAt'):
            self.assertNotIn(field,current)

    def test_same_startup_compatibility_retry_retains_approval_but_rechecks_content(self):
        self.enable();startup=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',startup);self.life.loading('begin',startup)
        self.assertTrue(self.enabled());self.assertEqual('attempted',self.life.activation_state()['entries']['test-plugin']['status'])
        self.put(self.package/'index.js','changed before compatibility retry')
        with contextlib.redirect_stdout(io.StringIO()):self.life.loading('begin',startup)
        self.assertFalse(self.enabled());self.assertEqual('changed',self.life.activation_state()['entries']['test-plugin']['status'])

    def test_user_edits_before_new_launch_are_loaded_with_current_fingerprint(self):
        self.enable()
        self.put(self.package/'index.js', 'user-owned edit before first launch')
        first = str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin', first)
        self.assertTrue(self.enabled())
        first_entry = self.life.activation_state()['entries']['test-plugin']
        self.assertTrue(first_entry['locallyEdited'])
        self.assertEqual(self.manager.dependencies().current(str(self.package))['sha256'], first_entry['fingerprint'])
        self.put(self.package/'index.js', 'second edit between launches')
        second = str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin', second)
        self.assertTrue(self.enabled())
        entry = self.life.activation_state()['entries']['test-plugin']
        self.assertEqual(second, entry['startup']); self.assertTrue(entry['locallyEdited'])
        self.assertEqual('second edit between launches', (self.package/'index.js').read_text())

    def test_fast_toggle_candidate_is_checked_before_load(self):
        with patch('sys.argv', ['plugin-manager.py', 'enable-list', 'test-plugin']), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.manager.main())
        self.put(self.package/'package.json', {'name':'test-plugin','version':'1.0.0',
                   'dependencies':{'missing-required-dependency':'1.0.0'},'dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        with contextlib.redirect_stdout(io.StringIO()):self.assertEqual(0,self.life.loading('begin', str(uuid.uuid4())))
        entry=self.life.activation_state()['entries']['test-plugin']
        self.assertEqual('failed',entry['status']);self.assertEqual('PLUGIN_DEPENDENCY_MISSING',entry['reason']);self.assertFalse(self.enabled())

    def test_edit_cannot_change_package_identity_under_existing_activation_name(self):
        self.enable()
        pkg=json.loads((self.package/'package.json').read_text())
        pkg['name']='another-plugin'
        self.put(self.package/'package.json',pkg)
        with contextlib.redirect_stdout(io.StringIO()):self.assertEqual(0,self.life.loading('begin',str(uuid.uuid4())))
        entry=self.life.activation_state()['entries']['test-plugin']
        self.assertEqual('failed',entry['status']);self.assertEqual('PLUGIN_IDENTITY_CHANGED',entry['reason']);self.assertFalse(self.enabled())
        self.assertEqual('another-plugin',json.loads((self.package/'package.json').read_text())['name'])

    def test_one_missing_dependency_does_not_discard_another_plugin_load_receipt(self):
        self.enable()
        broken=self.package.parent/'broken-plugin'
        self.put(broken/'package.json',{'name':'broken-plugin','version':'1.0.0','dependencies':{'absent-fixture':'1.0.0'},'dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        self.put(broken/'cordis.patch.yml','[]\n')
        self.life.queue_activation('broken-plugin','','1.0.0')
        doc=json.loads(self.manifest.read_text());doc['dsh']['profile']['bundles'].append('broken-plugin');self.put(self.manifest,doc)
        with patch.object(self.manager,'result') as result,contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,self.life.loading('begin',str(uuid.uuid4())))
        rows=self.life.activation_state()['entries']
        self.assertEqual('attempted',rows['test-plugin']['status']);self.assertEqual('failed',rows['broken-plugin']['status'])
        self.assertEqual('PLUGIN_DEPENDENCY_MISSING',rows['broken-plugin']['reason']);self.assertTrue(self.enabled())
        self.assertEqual(['broken-plugin'],[row['name'] for row in result.call_args.kwargs['failedPlugins']])

    def test_empty_cli_has_stable_error(self):
        with patch('sys.argv',['plugin-manager.py']),patch.object(self.manager,'result') as result:
            self.assertEqual(1,self.manager.main())
        self.assertEqual('不支持的插件操作',result.call_args.args[1])

    def test_load_receipt_checks_install_anchor_bytes_not_shadowed_user_edit(self):
        runtime = self.root/'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/test-plugin'
        self.put(runtime/'package.json', {'name':'test-plugin','version':'0.2.0-rc.2','dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        self.put(runtime/'cordis.patch.yml', '[]\n')
        self.put(runtime/'index.js', 'runtime bytes actually loaded by app-boot')
        self.put(self.package/'index.js', 'shadowed user edit to preserve')
        self.life.queue_activation('test-plugin', '', '1.0.0')
        doc=json.loads(self.manifest.read_text());doc['dsh']['profile']['bundles']=['test-plugin'];self.put(self.manifest,doc)
        actual=self.manager.dependencies().current(str(runtime))['sha256']
        with patch.object(self.manager.dependencies(), 'current', wraps=self.manager.dependencies().current) as graph:
            with contextlib.redirect_stdout(io.StringIO()):
                self.life.loading('begin', str(uuid.uuid4()))
            self.assertEqual(runtime.resolve(), Path(graph.call_args.args[0]).resolve())
        entry=self.life.activation_state()['entries']['test-plugin']
        self.assertEqual(actual, entry['fingerprint']);self.assertEqual('0.2.0-rc.2',entry['version'])
        self.assertEqual('shadowed user edit to preserve',(self.package/'index.js').read_text())

    def test_browser_health_only_is_not_plugin_load_evidence(self):
        self.enable();startup=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',startup);self.life.loading('complete',startup)
        entry=self.life.activation_state()['entries']['test-plugin']
        self.assertTrue(self.enabled());self.assertEqual('unknown',entry['status'])
        self.assertEqual('BROWSER_READY_UNVERIFIED',entry['reason']);self.assertEqual('NO_HOST_BINDING',entry['evidenceReason'])
        self.assertNotIn('loadedAt',entry)
        second=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):self.life.loading('begin',second)
        self.assertEqual('attempted',self.life.activation_state()['entries']['test-plugin']['status'])
        self.assertEqual(second,self.life.activation_state()['entries']['test-plugin']['startup'])

    def test_historical_loaded_receipt_is_not_rewritten_by_unrelated_startup(self):
        self.enable();state=self.life.activation_state()
        state['entries']['test-plugin'].update(status='loaded',startup=str(uuid.uuid4()),loadedAt=1)
        self.life.write(self.life.path('plugin-activations.json'),state)
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',str(uuid.uuid4()));self.life.loading('failed',str(uuid.uuid4()))
        self.assertEqual(state,self.life.activation_state());self.assertTrue(self.enabled())

    def test_unknown_same_startup_still_rechecks_source_changes(self):
        self.enable();startup=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',startup);self.life.loading('failed',startup)
            self.put(self.package/'index.js','changed after unconfirmed failure')
            self.life.loading('begin',startup)
        entry=self.life.activation_state()['entries']['test-plugin']
        self.assertEqual('changed',entry['status']);self.assertEqual('CONTENT_CHANGED_DURING_LOAD',entry['reason'])
        self.assertFalse(self.enabled());self.assertEqual('changed after unconfirmed failure',(self.package/'index.js').read_text())

    def test_unknown_new_startup_preserves_later_user_disable(self):
        self.enable();startup=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',startup);self.life.loading('failed',startup)
            self.assertEqual(0,self.manager.builtin.disable_plugin('test-plugin'))
            marker=Path(self.manager.builtin.marker_path('test-plugin'));before=marker.read_bytes()
            self.life.loading('begin',str(uuid.uuid4()))
        self.assertFalse(self.enabled());self.assertEqual(before,marker.read_bytes())
        self.assertEqual('disabled',self.life.activation_state()['entries']['test-plugin']['status'])

    def test_global_failure_does_not_disable_healthy_sibling(self):
        self.enable();sibling=self.package.parent/'sibling-plugin'
        self.put(sibling/'package.json',{'name':'sibling-plugin','version':'1.0.0','dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        self.put(sibling/'cordis.patch.yml','[]\n');self.put(sibling/'index.js','export {};')
        self.life.queue_activation('sibling-plugin',self.manager.dependencies().current(str(sibling))['sha256'],'1.0.0')
        doc=json.loads(self.manifest.read_text());doc['dependencies']['sibling-plugin']='^1.0.0'
        doc['dsh']['profile']['bundles'].append('sibling-plugin');self.put(self.manifest,doc)
        startup=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',startup)
            with patch.object(self.manager,'result') as result:
                self.life.loading('failed',startup)
        self.assertEqual(['test-plugin','sibling-plugin'],json.loads(self.manifest.read_text())['dsh']['profile']['bundles'])
        for name,entry in self.life.activation_state()['entries'].items():
            self.assertEqual('unknown',entry['status']);self.assertEqual('STARTUP_FAILURE_UNATTRIBUTED',entry['reason'])
            self.assertFalse(Path(self.manager.builtin.marker_path(name)).exists())
        self.assertEqual([],result.call_args.kwargs['failedPlugins'])
        self.assertEqual({'test-plugin','sibling-plugin'},{row['name'] for row in result.call_args.kwargs['unattributedPlugins']})

    def test_runtime_result_does_not_rescan_or_execute_plugin(self):
        self.enable();startup=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):self.life.loading('begin',startup)
        with patch.object(self.manager,'resolve_plugin_dir',side_effect=AssertionError('runtime result must not scan disk')), \
             patch.object(self.manager.dependencies(),'current',side_effect=AssertionError('runtime result must not hash code')), \
             contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('complete',startup)
        self.assertTrue(self.enabled());self.assertEqual('unknown',self.life.activation_state()['entries']['test-plugin']['status'])

    def test_sidecar_self_report_never_creates_host_authority(self):
        # 这些都是可写观察数据；包括看似完整的 HOST 字段也不是 A5 的可信绑定，不能造正向部署证明。
        cases=['observer','unattributed','self-reported-host','missing-startup','old-startup','missing-generation',
               'old-generation','missing-instance','old-instance','expired','future','different-version',
               'changed-content','duplicate-entry','different-module','different-parent','truncated']
        for action in ('failed','complete'):
            for case in cases:
                with self.subTest(action=action,case=case):
                    self.enable();startup=str(uuid.uuid4())
                    with contextlib.redirect_stdout(io.StringIO()):self.life.loading('begin',startup)
                    content=self.manager.dependencies().current(str(self.package))['sha256']
                    identity={'identitySource':'HOST_START_TICKET','startupId':startup,'generation':'7',
                              'instance':str(uuid.uuid4()),'observedAt':int(time.time()*1000)}
                    row={'name':'test-plugin','version':'1.0.0','profile':'web','state':'failed' if action=='failed' else 'loaded',
                         'entries':[{'entryId':'fixture-entry','moduleHash':'a'*64,'parentId':'','group':False,
                                     'phase':'failed' if action=='failed' else 'active','diagnostic':''}],
                         'identity':identity,'contentFingerprint':content,'contentReason':'CONTENT_MATCHED'}
                    evidence={'schema':1,'profile':'web',**identity,'installedPlugins':[row],'truncated':False}
                    if case=='observer':identity.update(identitySource='OBSERVER_SESSION',startupId='unknown')
                    elif case=='unattributed':identity.update(identitySource='UNATTRIBUTED',startupId='unknown',generation='',instance='')
                    elif case=='missing-startup':identity['startupId']=''
                    elif case=='old-startup':identity['startupId']=str(uuid.uuid4())
                    elif case=='missing-generation':identity['generation']=''
                    elif case=='old-generation':identity['generation']='6'
                    elif case=='missing-instance':identity['instance']=''
                    elif case=='old-instance':identity['instance']=str(uuid.uuid4())
                    elif case=='expired':identity['observedAt']-=60000
                    elif case=='future':identity['observedAt']+=60000
                    elif case=='different-version':row['version']='2.0.0'
                    elif case=='changed-content':self.put(self.package/'index.js','same-version changed content')
                    elif case=='duplicate-entry':row['entries'].append(dict(row['entries'][0]))
                    elif case=='different-module':row['entries'][0]['moduleHash']='b'*64
                    elif case=='different-parent':row['entries'][0]['parentId']='other-group'
                    elif case=='truncated':evidence['truncated']=True
                    evidence.update(identity)
                    self.put(self.root/'root/.dsha-plugin-startup-evidence.json',evidence)
                    with contextlib.redirect_stdout(io.StringIO()):self.life.loading(action,startup)
                    entry=self.life.activation_state()['entries']['test-plugin']
                    self.assertTrue(self.enabled());self.assertEqual('unknown',entry['status'])
                    self.assertEqual('NO_HOST_BINDING',entry['evidenceReason']);self.assertNotIn('loadedAt',entry)
                    self.assertEqual('CONTENT_UNVERIFIED',entry['contentReason'])

    def test_safe_mode_restores_only_unchanged_previously_enabled_source(self):
        self.enable()
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,self.life.safe_mode('on'));self.assertFalse(self.enabled());self.assertEqual(0,self.life.safe_mode('off'));self.assertTrue(self.enabled())
            self.life.safe_mode('on');self.put(self.package/'index.js','edited during safe mode');self.assertEqual(1,self.life.safe_mode('off'))
        self.assertFalse(self.enabled());self.assertEqual('edited during safe mode',(self.package/'index.js').read_text())

    def test_pending_previews_survive_new_manager_and_corrupt_record_is_retained(self):
        preview=self.preview();pending,unreadable=self.life.pending_previews();self.assertEqual(1,len(pending));self.assertEqual(0,unreadable)
        marker=self.root/'root/.dsh/plugin-previews'/uuid.uuid4().hex/'preview.json';self.put(marker,'{broken')
        pending,unreadable=self.life.pending_previews();self.assertEqual(1,len(pending));self.assertEqual(1,unreadable);self.assertTrue(marker.exists())

    def restored(self,name):
        group=str(uuid.uuid4());node='package-'+'a'*20
        path=self.root/'root/dsha-native-plugin-reviews'/group/'packages'/node
        self.put(path/'package.json',{'name':name,'version':'2.0.0','dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        self.put(path/'cordis.patch.yml','[]\n');self.put(path/'index.js','owned restored source')
        with patch.object(self.manager,'result') as result:
            self.life.review_restored(group,node);preview=result.call_args.kwargs['preview']
        return path,preview

    def test_restored_name_conflict_keeps_current_installation_untouched(self):
        source,preview=self.restored('test-plugin');self.assertTrue(preview['items'][0]['existingConflict'])
        with self.assertRaisesRegex(ValueError,'同名插件'):
            self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        self.assertEqual('1.0.0',json.loads((self.package/'package.json').read_text())['version']);self.assertTrue((source/'index.js').is_file())

    def test_plugin_export_excludes_credentials_but_keeps_native_lock_and_originals(self):
        self.put(self.package/'.npmrc','//registry.invalid/:_authToken=owned-secret')
        self.put(self.package/'.env','PRIVATE_API_KEY=owned-secret')
        self.put(self.package/'pnpm-lock.yaml',"lockfileVersion: '9.0'\nimporters: {}\n")
        self.put(self.package/'.dsha-dependencies.json',{'oldSnapshot':True})
        with patch.object(self.manager,'result') as result:
            self.manager.cmd_export('["test-plugin"]','/root/export.tgz')
            self.assertTrue(result.call_args.kwargs['excluded'])
        with tarfile.open(self.root/'root/export.tgz','r:gz') as archive:
            names=archive.getnames();self.assertTrue(any(name.endswith('/pnpm-lock.yaml') for name in names))
            self.assertFalse(any(name.endswith(('/.npmrc','/.env','/.dsha-dependencies.json')) for name in names))
        self.assertIn('owned-secret',(self.package/'.npmrc').read_text());self.assertTrue((self.package/'.env').exists())

    def directory_link(self, link, target):
        link.parent.mkdir(parents=True,exist_ok=True)
        if os.name=='nt':
            # Junctions exercise real external directory resolution without requiring
            # Developer Mode. Both paths belong to this fixture's private temp root.
            quote=lambda value:"'"+str(value).replace("'","''")+"'"
            subprocess.run(['powershell','-NoProfile','-Command','New-Item -ItemType Junction -Path '+quote(link)+' -Target '+quote(target)+' | Out-Null'],check=True,capture_output=True)
        else:os.symlink(target,link,target_is_directory=True)

    def test_export_external_source_link_fails_without_touching_original(self):
        private=self.root/'private';self.put(private/'secret.txt','fixture-private-content')
        self.directory_link(self.package/'leak',private)
        with contextlib.redirect_stdout(io.StringIO()),self.assertRaisesRegex(ValueError,'外部链接|未归属'):
            self.manager.cmd_export('["test-plugin"]','/root/export.tgz')
        self.assertFalse((self.root/'root/export.tgz').exists())
        self.assertEqual('fixture-private-content',(private/'secret.txt').read_text())
        self.assertTrue((self.package/'leak').exists())

    def test_export_shared_dependencies_materializes_only_bound_packages_and_bins(self):
        shared=self.root/'root/.dsh/node_modules';helper=shared/'export-helper'
        self.put(helper/'package.json',{'name':'export-helper','version':'1.0.0','bin':{'export-command':'bin/run.js'}})
        self.put(helper/'bin/run.js','#!/usr/bin/env node\nrequire("../index.js");')
        self.put(helper/'index.js','module.exports=1;')
        self.put(shared/'unrelated/package.json',{'name':'unrelated','version':'1.0.0'})
        self.put(shared/'unrelated/private.txt','unrelated-original')
        self.put(self.package/'package.json',{'name':'test-plugin','version':'1.0.0','dependencies':{'export-helper':'1.0.0'},'dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        self.directory_link(self.package/'node_modules',shared)
        with contextlib.redirect_stdout(io.StringIO()),patch.object(self.manager,'result'):
            self.manager.cmd_export('["test-plugin"]','/root/export.tgz')
        with tarfile.open(self.root/'root/export.tgz','r:gz') as archive:
            names=archive.getnames();self.assertIn('plugins/test-plugin/node_modules/export-helper/index.js',names)
            self.assertFalse(any('/unrelated/' in name for name in names))
            command=archive.getmember('plugins/test-plugin/node_modules/.bin/export-command')
            self.assertTrue(command.issym());self.assertEqual('../export-helper/bin/run.js',command.linkname)
        self.assertEqual('unrelated-original',(shared/'unrelated/private.txt').read_text())

    def test_plugin_without_valid_version_cannot_reach_review_or_activation(self):
        original=json.loads((self.package/'package.json').read_text(encoding='utf8'))
        for bad in (None,'','latest',1,'1.2','1.0.0-01','1.0.0-rc.01'):
            pkg=dict(original)
            if bad is None:pkg.pop('version')
            else:pkg['version']=bad
            self.put(self.package/'package.json',pkg)
            with self.assertRaisesRegex(ValueError,'version'):
                self.manager.plugin_package(self.package)
            with self.assertRaisesRegex(ValueError,'version'):
                self.life.review_existing('test-plugin')
            self.assertFalse(self.enabled())
        pkg=dict(original,version='0.1.7-rc.2+5')
        self.put(self.package/'package.json',pkg)
        self.assertEqual('0.1.7-rc.2+5',self.manager.plugin_package(self.package)['version'])

    @unittest.skipIf(os.name=='nt','恢复插件真实软链启用在 Android/Linux 验证')
    def test_restored_new_name_activates_from_preserved_dependency_group(self):
        source,preview=self.restored('restored-fixture')
        with contextlib.redirect_stdout(io.StringIO()):self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        manifest=json.loads(self.manifest.read_text());self.assertIn('restored-fixture',manifest['dsh']['profile']['bundles'])
        self.assertEqual(source.resolve(),(self.manifest.parent/'node_modules/restored-fixture').resolve())
        self.assertEqual('queued',self.life.activation_state()['entries']['restored-fixture']['status'])
        self.assertTrue((source/'index.js').is_file());self.assertFalse(self.manager.transactions().pending())


class LegacyMigrationTest(unittest.TestCase):
    """仅临时假 root 的纯 Python 合成测试；不启动 Node、guest 或插件入口。"""
    put = ReviewTest.put

    def setUp(self):
        guards = contextlib.ExitStack(); self.addCleanup(guards.close)
        for target in ('subprocess.Popen', 'socket.create_connection', 'urllib.request.urlopen'):
            guards.enter_context(patch(target, side_effect=AssertionError('synthetic test must not launch or connect')))
        ReviewTest.setUp(self); self.addCleanup(ReviewTest.tearDown, self)
        self.assertEqual(self.root / 'root/.dsh', Path(self.manager.local('/root/.dsh')))
        guards.enter_context(patch.object(self.life, 'compatibility_reports', side_effect=lambda packages: [
            {'compatibility': 'unconstrained', 'compatibilityEvidence': 'synthetic-only'} for _ in packages]))

    def legacy(self, name='legacy-plugin', marker='DSHA_REVIEW_REQUIRED\n'):
        directory = self.root / 'root/.dsh/plugin-src' / name
        self.put(directory / 'package.json', {'name': name, 'version': '1.0.0', 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}})
        self.put(directory / 'cordis.patch.yml', '[]\n')
        self.put(directory / 'index.js', "throw Error('must never execute legacy plugin');")
        disabled = Path(self.manager.builtin.marker_path(name)); self.put(disabled, marker)
        document = json.loads(self.manifest.read_text())
        document['dependencies'][name] = 'link:/root/.dsh/plugin-src/' + name
        self.put(self.manifest, document)
        return directory, disabled

    def cli(self, *args):
        output = io.StringIO()
        with patch('sys.argv', ['plugin-manager.py', *args]), contextlib.redirect_stdout(output):
            code = self.manager.main()
        rows = [json.loads(line.removeprefix('PLUGIN_RESULT: ')) for line in output.getvalue().splitlines()
                if line.startswith('PLUGIN_RESULT: ')]
        self.assertEqual(1, len(rows), output.getvalue())
        return code, rows[0]

    def denied_reports(self, packages):
        states = {'bad-version': ('incompatible', 'PLUGIN_INCOMPATIBLE'),
                  'bad-platform': ('unsupported-platform', 'PLUGIN_UNSUPPORTED_PLATFORM'),
                  'bad-check': ('check-failed', 'PLUGIN_COMPATIBILITY_CHECK_FAILED')}
        return [dict(compatibility=states[pkg['name']][0], compatibilityCode=states[pkg['name']][1],
                     compatibilityMessage='合成兼容拒绝' * 100, versionExempted=False) for pkg in packages]

    def test_preflight_denials_complete_without_queuing_and_explicit_enable_still_denies(self):
        markers = {}
        for name in ('bad-version', 'bad-platform', 'bad-check'):
            _, markers[name] = self.legacy(name)
        before = self.manifest.read_bytes()
        with patch.object(self.life, 'compatibility_reports', side_effect=self.denied_reports), \
             patch.object(self.life, 'queue_activation') as queue, patch.object(self.manager.builtin, 'enable_plugin') as enable:
            code, result = self.cli('migrate-review-markers')
            self.assertEqual(0, code); self.assertEqual('ok', result['status'])
            self.assertEqual(3, result['blockedPluginCount']); self.assertEqual([], result['activated'])
            self.assertEqual(set(markers), {row['name'] for row in result['blockedPlugins']})
            self.assertTrue(all(len(row['message']) <= 400 for row in result['blockedPlugins']))
            self.assertEqual(['LEGACY_PLUGIN_COMPATIBILITY_BLOCKED'], result['warnings'])
            for name in markers:
                code, failure = self.cli('enable-state', name)
                self.assertEqual(1, code); self.assertEqual('error', failure['status'])
                self.assertIn(failure['errorCode'], ('PLUGIN_INCOMPATIBLE', 'PLUGIN_UNSUPPORTED_PLATFORM', 'PLUGIN_COMPATIBILITY_CHECK_FAILED'))
            queue.assert_not_called(); enable.assert_not_called()
        self.assertEqual(before, self.manifest.read_bytes())
        self.assertEqual({}, self.life.activation_state()['entries'])
        for marker in markers.values(): self.assertEqual(b'DSHA_REVIEW_REQUIRED\n', marker.read_bytes())

    def test_explicit_install_and_update_still_reject_before_dependency_preparation(self):
        directory, marker = self.legacy('bad-version')
        incoming = self.root / 'incoming'; shutil.copytree(directory, incoming)
        package = json.loads((incoming / 'package.json').read_text()); package['version'] = '2.0.0'
        self.put(incoming / 'package.json', package)
        archive = self.root / 'incoming.tgz'
        with tarfile.open(archive, 'w:gz') as stream: stream.add(incoming, arcname='plugin')
        target = self.life.target_snapshot('bad-version')
        self.life.write(self.life.path('plugin-updates.json'), {'bad-version': {
            'available': True, 'checkedAt': int(time.time()), 'installedVersion': '1.0.0', 'targetSnapshot': target,
            'request': {'command': 'file ' + str(archive), 'name': 'bad-version'}}})
        before = self.manifest.read_bytes()
        with patch.object(self.life, 'compatibility_reports', side_effect=self.denied_reports), \
             patch.object(self.manager, 'prepare_dependencies') as dependencies:
            for args in (('inspect', json.dumps({'command': 'file ' + str(archive)})), ('prepare-update', 'bad-version')):
                with self.subTest(command=args[0]):
                    code, result = self.cli(*args)
                    self.assertEqual(1, code); self.assertEqual('error', result['status'])
                    self.assertEqual('PLUGIN_INCOMPATIBLE', result['errorCode'])
            dependencies.assert_not_called()
        self.assertEqual(before, self.manifest.read_bytes()); self.assertTrue(archive.is_file())
        self.assertEqual(b'DSHA_REVIEW_REQUIRED\n', marker.read_bytes())

    def test_bad_sibling_does_not_block_healthy_migration_or_change_user_safe_mode_markers(self):
        _, bad = self.legacy('bad-version'); _, healthy = self.legacy('healthy-plugin')
        _, user = self.legacy('user-disabled', ''); _, safe = self.legacy('safe-disabled', 'DSHA_SAFE_MODE\n')
        document = json.loads(self.manifest.read_text()); document['dsh']['profile']['bundles'] = ['healthy-builtin-fixture']
        self.put(self.manifest, document)
        def reports(packages):
            return [self.denied_reports([pkg])[0] if pkg['name'] == 'bad-version' else
                    {'compatibility': 'unconstrained', 'compatibilityEvidence': 'synthetic-only'} for pkg in packages]
        link = patch.object(self.manager.os, 'symlink', side_effect=lambda src,dst,**_: shutil.copytree((Path(dst).parent/src).resolve(),dst)) if os.name=='nt' else contextlib.nullcontext()
        with link, patch.object(self.life, 'compatibility_reports', side_effect=reports):
            code, result = self.cli('migrate-review-markers')
        self.assertEqual(0, code); self.assertEqual(['healthy-plugin'], result['activated'])
        self.assertEqual(b'DSHA_REVIEW_REQUIRED\n', bad.read_bytes()); self.assertFalse(healthy.exists())
        self.assertEqual(b'', user.read_bytes()); self.assertEqual(b'DSHA_SAFE_MODE\n', safe.read_bytes())
        self.assertEqual(['healthy-builtin-fixture', 'healthy-plugin'], json.loads(self.manifest.read_text())['dsh']['profile']['bundles'])
        self.assertEqual({'healthy-plugin'}, set(self.life.activation_state()['entries']))

    def test_blocked_diagnostics_are_bounded(self):
        for index in range(55): self.legacy('blocked-' + str(index))
        error = self.manager.PluginPolicyError('PLUGIN_INCOMPATIBLE', 'x' * 1000, stage='enable')
        with patch.object(self.life, 'require_compatible', side_effect=error), patch.object(self.life, 'queue_activation') as queue:
            code, result = self.cli('migrate-review-markers')
        self.assertEqual(0, code); self.assertEqual(55, result['blockedPluginCount'])
        self.assertEqual(50, len(result['blockedPlugins'])); self.assertTrue(result['blockedPluginsTruncated'])
        self.assertTrue(all(len(row['message']) == 400 for row in result['blockedPlugins'])); queue.assert_not_called()

    def test_activation_write_failure_is_not_success(self):
        _, marker = self.legacy()
        with patch.object(self.life, 'write', side_effect=OSError('synthetic write failed')), \
             patch.object(self.manager.builtin, 'enable_plugin') as enable:
            code, result = self.cli('migrate-review-markers')
        self.assertEqual(1, code); self.assertEqual('error', result['status']); enable.assert_not_called()
        self.assertEqual(b'DSHA_REVIEW_REQUIRED\n', marker.read_bytes())

    def test_enable_nonzero_is_not_success(self):
        _, marker = self.legacy()
        with patch.object(self.manager.builtin, 'enable_plugin', return_value=1) as enable:
            code, result = self.cli('migrate-review-markers')
        self.assertEqual(1, code); self.assertEqual('error', result['status']); enable.assert_called_once()
        self.assertEqual(b'DSHA_REVIEW_REQUIRED\n', marker.read_bytes())
        self.assertEqual('queued', self.life.activation_state()['entries']['legacy-plugin']['status'])

    def test_policy_errors_from_queue_or_enable_are_not_preflight_skips(self):
        self.legacy()
        for owner, method in ((self.life, 'queue_activation'), (self.manager.builtin, 'enable_plugin')):
            with self.subTest(method=method), patch.object(owner, method, side_effect=self.manager.PluginPolicyError('PLUGIN_INCOMPATIBLE', 'not a preflight denial')) as action:
                code, result = self.cli('migrate-review-markers')
                self.assertEqual(1, code); self.assertEqual('error', result['status']); action.assert_called_once()
                self.assertEqual('PLUGIN_INCOMPATIBLE', result['errorCode'])

    def test_source_change_from_preflight_is_not_a_compatibility_skip(self):
        self.legacy()
        with patch.object(self.life, 'require_compatible', side_effect=self.manager.PluginPolicyError('PLUGIN_SOURCE_CHANGED', 'source changed')), \
             patch.object(self.life, 'queue_activation') as queue:
            code, result = self.cli('migrate-review-markers')
        self.assertEqual(1, code); self.assertEqual('PLUGIN_SOURCE_CHANGED', result['errorCode']); queue.assert_not_called()

    def test_pending_after_initial_guard_still_blocks_under_writer_lock(self):
        self.legacy()
        with patch.object(self.manager.transactions(), 'pending', side_effect=[[], ['synthetic-pending']]), \
             patch.object(self.life, 'queue_activation') as queue:
            code, result = self.cli('migrate-review-markers')
        self.assertEqual(1, code); self.assertEqual('pending', result['status']); queue.assert_not_called()

    def test_pending_and_source_errors_from_entity_check_are_not_bad_package_skips(self):
        self.legacy()
        for error, status in ((self.manager.PluginInstallPending('pending'), 'pending'),
                              (self.manager.PluginPolicyError('PLUGIN_SOURCE_CHANGED', 'changed'), 'error')):
            with self.subTest(status=status), patch.object(self.manager, 'plugin_package', side_effect=error):
                code, result = self.cli('migrate-review-markers')
                self.assertEqual(1, code); self.assertEqual(status, result['status'])

    def test_unknown_from_enable_is_retained_and_not_reported_complete(self):
        _, marker = self.legacy()
        with patch.object(self.manager.builtin, 'enable_plugin', side_effect=self.manager.PackageCommandUnknown('synthetic unknown')) as enable:
            code, result = self.cli('migrate-review-markers')
        self.assertEqual(1, code); self.assertEqual('unknown', result['status']); self.assertTrue(result['executionUnknown'])
        enable.assert_called_once(); self.assertEqual(b'DSHA_REVIEW_REQUIRED\n', marker.read_bytes())
        self.assertEqual('queued', self.life.activation_state()['entries']['legacy-plugin']['status'])


if __name__=='__main__':unittest.main(verbosity=2)
