import importlib.util, json, tempfile, unittest, os
from pathlib import Path
from unittest.mock import patch

SCRIPT=Path(__file__).resolve().parents[1]/'app/src/main/assets/rc1-migration.py'
spec=importlib.util.spec_from_file_location('rc1_migration',SCRIPT); mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)

class Migration(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='dsha-rc1-');self.root=Path(self.temp.name);self.dsh=self.root/'.dsh';self.dsh.mkdir();self.state=self.root/'host-state'
        self.runtime_version=json.loads((SCRIPT.parent/'runtime-patches.json').read_text())['dshVersion']
        (self.dsh/'runtime-patches.json').write_text(json.dumps({'schema':1,'dshVersion':self.runtime_version}))
        (self.dsh/'settings.yaml').write_text('llm: {model: legacy}\n',encoding='utf8')
        (self.dsh/'sessions').mkdir();(self.dsh/'sessions/session.v3.jsonl').write_text('{"version":3}\n',encoding='utf8')
    def tearDown(self):self.temp.cleanup()
    def prepare(self):return mod.prepare(str(self.root),str(self.state),'boot')
    def finalize(self):return mod.finalize(str(self.root),str(self.state),'boot')
    def folder(self):return self.state/'generations'/json.loads((self.state/'current.json').read_text())['generation']
    def doc(self):return json.loads((self.folder()/'prepare.json').read_text())
    def result(self):return json.loads((self.folder()/'receipt.json').read_text())
    def verified(self):
        doc=self.doc()
        (self.folder()/'settings-result.json').write_text(json.dumps(dict(version=2, generation=doc['generation'],
            sourceSha256=mod.settings_sha256(doc), startupId='boot', inputDigest=doc['inputDigest'],
            status='verified', expectedSections=['first'], sections={'first':{'status':'verified'}})))
    def capture(self, fn, *args):
        import contextlib, io
        output=io.StringIO()
        with contextlib.redirect_stdout(output): code=fn(*args)
        lines=[line for line in output.getvalue().splitlines() if line.startswith('DSHA_RC1_MIGRATION=')]
        self.assertEqual(len(lines),1,output.getvalue())
        return code,json.loads(lines[0].split('=',1)[1])
    def write_ledger(self, value):
        (self.folder()/'settings-result.json').write_text(json.dumps(value))
    def ledger(self):return json.loads((self.folder()/'settings-result.json').read_text())
    def current(self):return json.loads((self.state/'current.json').read_text())
    def test_new_records_and_host_checkpoint_use_deployed_runtime_version(self):
        self.assertEqual(self.prepare(),0);self.verified();self.assertEqual(self.finalize(),0)
        self.assertEqual(self.doc()['dshVersion'],self.runtime_version)
        self.assertEqual(self.result()['dshVersion'],self.runtime_version)
        self.assertEqual(self.result()['runtimeDshVersion'],self.runtime_version)
        self.assertEqual(json.loads((self.folder()/'reuse.json').read_text())['dshVersion'],self.runtime_version)
    def test_missing_or_wrong_deployed_registry_never_borrows_version_from_old_receipt(self):
        registry=self.dsh/'runtime-patches.json';registry.unlink()
        with self.assertRaisesRegex(ValueError,'REGISTRY_UNAVAILABLE'):self.prepare()
        self.assertFalse(self.state.exists());self.assertTrue((self.dsh/'settings.yaml').is_file())
        for value in ({'schema':1,'dshVersion':False},{'schema':2,'dshVersion':self.runtime_version},{'schema':1,'dshVersion':'unknown'}):
            registry.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError,'REGISTRY_VERSION'):self.prepare()
        self.assertFalse(self.state.exists())
    def test_old_committed_generation_does_not_become_new_runtime_host_cache(self):
        registry=self.dsh/'runtime-patches.json';registry.write_text(json.dumps({'schema':1,'dshVersion':'0.2.0-rc.2'}))
        self.prepare();self.verified();self.finalize();old=self.folder()
        prepared=(old/'prepare.json').read_bytes();receipt=(old/'receipt.json').read_bytes()
        (old/'reuse.json').unlink()
        registry.write_text(json.dumps({'schema':1,'dshVersion':self.runtime_version}))
        with patch.object(mod.Resolver,'files',side_effect=AssertionError('version change must not replay or rescan old business sources')):
            self.assertEqual(self.prepare(),0);self.assertEqual(self.finalize(),0)
        self.assertEqual(old,self.folder());self.assertEqual(prepared,(old/'prepare.json').read_bytes())
        self.assertEqual(receipt,(old/'receipt.json').read_bytes());self.assertFalse((old/'reuse.json').exists())
    def test_old_pending_history_never_commits_or_relabels_old_proof(self):
        registry=self.dsh/'runtime-patches.json';registry.write_text(json.dumps({'schema':1,'dshVersion':'0.2.0-rc.2'}))
        self.prepare();self.finalize();old=self.folder();self.assertEqual(self.result()['status'],'pending')
        registry.write_text(json.dumps({'schema':1,'dshVersion':self.runtime_version}))
        before=(old/'receipt.json').read_bytes()
        self.prepare();self.verified();code,payload=self.capture(self.finalize);self.assertEqual(code,0)
        self.assertEqual(payload['status'],'pending');self.assertEqual(payload['verificationScope'],'historical')
        self.assertEqual(old,self.folder());self.assertEqual(self.result()['status'],'pending')
        self.assertEqual(before,(old/'receipt.json').read_bytes())
        self.assertEqual(self.result()['dshVersion'],'0.2.0-rc.2')
        self.assertEqual(self.result()['runtimeDshVersion'],'0.2.0-rc.2')
        self.assertFalse((old/'reuse.json').exists())
    def test_runtime_registry_link_does_not_supply_version(self):
        registry=self.dsh/'runtime-patches.json';registry.unlink()
        external=self.root/'external-registry.json';external.write_text(json.dumps({'schema':1,'dshVersion':self.runtime_version}))
        self.link(external,registry)
        with self.assertRaisesRegex(ValueError,'REGISTRY_TYPE'):self.prepare()
        self.assertFalse(self.state.exists())
    def test_real_pending_sequence_never_commits_without_settings_readback(self):
        self.assertEqual(self.prepare(),0);(self.dsh/'settings.yaml').rename(self.dsh/'settings.yaml.imported')
        self.assertEqual(self.finalize(),0);self.assertEqual(self.result()['status'],'pending')
        for _ in range(3):
            self.assertEqual(self.prepare(),0);self.assertFalse((self.dsh/'settings.yaml').exists());self.finalize();self.assertEqual(self.result()['status'],'pending')
        self.verified();code,payload=self.capture(self.finalize)
        self.assertEqual(code,0);self.assertEqual(payload['status'],'pending')
        self.assertEqual(self.result()['status'],'pending');self.assertEqual(self.result()['sessionsStatus'],'preserved-awaiting-runtime-open')
    def test_new_input_after_commit_has_new_generation(self):
        self.prepare();first=self.folder();self.verified();self.finalize();(self.dsh/'settings.yaml').write_text('llm: {model: other}\n')
        self.prepare();self.assertNotEqual(first,self.folder());self.assertTrue(first.is_dir())
    def test_both_settings_inputs_require_exact_match_and_valid_hashes(self):
        root={'path':'/root/.dsh','device':'1','inode':'2'}
        stamp=lambda inputs:dict(dataRoot=root,inputs=inputs)
        a='a'*64;b='b'*64
        self.assertTrue(mod.same_input(stamp({'settings.yaml':a}),stamp({'settings.yaml.imported':a})))
        self.assertFalse(mod.same_input(stamp({'settings.yaml':a}),stamp({'settings.yaml':a,'settings.yaml.imported':a})))
        self.assertFalse(mod.same_input(stamp({'settings.yaml':a,'settings.yaml.imported':b}),stamp({'settings.yaml':a,'settings.yaml.imported':a})))
        self.assertTrue(mod.same_input(stamp({'settings.yaml':a,'settings.yaml.imported':b}),stamp({'settings.yaml':a,'settings.yaml.imported':b})))
        self.assertFalse(mod.same_input(stamp({'settings.yaml':'invalid'}),stamp({'settings.yaml.imported':'invalid'})))
        self.assertFalse(mod.same_input(stamp({'unknown':a}),stamp({'unknown':a})))
    def test_restore_epoch_same_bytes_has_new_generation(self):
        self.prepare();first=self.folder();(self.dsh/'.dsha-rc1-restore-generation').write_text('restore-operation-2');self.prepare();self.assertNotEqual(first,self.folder())
    def test_completed_ordinary_prepare_does_not_scan_sessions(self):
        self.prepare();self.verified();self.finalize()
        with patch.object(mod.Resolver,'files',side_effect=AssertionError('ordinary startup must not enumerate')):self.prepare()
    def test_reuse_checkpoint_only_follows_complete_same_generation_receipt(self):
        self.prepare();self.finalize();self.assertFalse((self.folder()/'reuse.json').exists())
        self.verified();self.finalize();checkpoint=json.loads((self.folder()/'reuse.json').read_text())
        self.assertEqual(checkpoint['inputs'],self.doc()['inputs']);self.assertEqual(checkpoint['generation'],self.result()['generation'])
        self.assertEqual(checkpoint['status'],'prepared');self.assertTrue(checkpoint['protectionComplete']);self.assertNotIn('sources',checkpoint)
        self.assertLess((self.folder()/'reuse.json').stat().st_size,4096)
        (self.folder()/'reuse.json').unlink();self.finalize();self.assertTrue((self.folder()/'reuse.json').is_file())
        (self.folder()/'reuse.json').write_text('{broken')
        self.assertEqual(self.finalize(),0);self.assertEqual(json.loads((self.folder()/'reuse.json').read_text()),checkpoint)
        bad=dict(self.result(),sourcePreserved=False)
        (self.folder()/'receipt.json').write_text(json.dumps(bad));(self.folder()/'reuse.json').unlink()
        self.assertFalse(mod.publish_reuse(self.folder(),self.doc(),json.loads((self.state/'current.json').read_text()),bad,self.runtime_version))
        self.assertFalse((self.folder()/'reuse.json').exists())
    def test_partial_or_legacy_committed_receipt_is_rechecked_instead_of_upgraded(self):
        self.prepare();self.verified();self.finalize();(self.folder()/'reuse.json').unlink()
        receipt=self.result();receipt['version']=1;(self.folder()/'receipt.json').write_text(json.dumps(receipt))
        (self.folder()/self.doc()['sources'][0]['snapshot']).write_text('damaged')
        self.assertEqual(self.finalize(),1);self.assertFalse((self.folder()/'reuse.json').exists())
    def test_missing_source_and_changed_session_do_not_invalidate_content_copy(self):
        self.prepare();(self.dsh/'settings.yaml').unlink();(self.dsh/'sessions/session.v3.jsonl').write_text('changed')
        code,payload=self.capture(self.finalize)
        self.assertEqual(code,0);self.assertEqual(payload['contentCopyStatus'],'verified')
        self.assertEqual(payload['sourceObjectIdentity'],'unknown');self.assertNotIn('sourcePreserved',payload)
        self.assertTrue(any(w.startswith('SOURCE_MISSING_OR_CHANGED:') for w in payload['warnings']))
        self.assertEqual(self.result()['status'],'pending')
    def test_snapshot_modified_detected_even_source_unchanged(self):
        self.prepare();(self.folder()/self.doc()['sources'][0]['snapshot']).write_text('changed');self.assertEqual(self.finalize(),1)
    def test_source_replaced_with_same_bytes_keeps_copy_and_unknown_identity(self):
        self.prepare();source=self.dsh/'settings.yaml';value=source.read_text();source.unlink();source.write_text(value)
        code,payload=self.capture(self.finalize)
        self.assertEqual(code,0);self.assertEqual(payload['contentCopyStatus'],'verified')
        self.assertEqual(payload['sourceObjectIdentity'],'unknown');self.assertNotIn('sourcePreserved',payload)
        self.assertTrue(self.result()['sourcePreserved'])
    def test_snapshot_io_error_blocks_prepared_and_never_creates_current(self):
        with patch.object(mod,'snapshot_row',side_effect=OSError('disk full')):self.assertEqual(self.prepare(),1)
        self.assertFalse((self.state/'current.json').exists());self.assertTrue((self.dsh/'settings.yaml').is_file())
    def test_low_space_preflight_does_not_copy_or_create_generation(self):
        with patch.object(mod.shutil,'disk_usage',return_value=type('Space',(),{'free':0})()),patch.object(mod,'snapshot_row',side_effect=AssertionError('must preflight before copy')):
            self.assertEqual(self.prepare(),1)
        self.assertFalse((self.state/'generations').exists());self.assertFalse((self.state/'current.json').exists())
    def test_same_complete_inventory_reuses_verified_failed_snapshots(self):
        crew=self.dsh/'.agent-presets/crew';crew.mkdir(parents=True);(crew/'agent.cordis.yml').write_text('- id: old\n')
        generations=[];counts=[]
        with patch.object(mod.shutil,'copyfile',side_effect=OSError('candidate disk write unavailable')):
            for _ in range(3):
                self.assertEqual(self.prepare(),1)
                folder=next((self.state/'generations').iterdir());doc=json.loads((folder/'prepare.json').read_text());generations.append(doc['generation']);counts.append(len(list((folder/'snapshots').iterdir())))
        self.assertEqual(len(set(generations)),1);self.assertEqual(len(set(counts)),1)
        self.assertEqual(self.prepare(),0);self.assertEqual(self.doc()['generation'],generations[0])
    def test_failed_inventory_identity_changes_start_new_generation_without_deleting_old(self):
        crew=self.dsh/'.agent-presets/crew';crew.mkdir(parents=True);(crew/'agent.cordis.yml').write_text('- id: old\n')
        with patch.object(mod.shutil,'copyfile',side_effect=OSError('candidate failure')):self.assertEqual(self.prepare(),1)
        old=next((self.state/'generations').iterdir());(self.dsh/'sessions/session.v3.jsonl').write_text('new contents')
        self.assertEqual(self.prepare(),0);self.assertNotEqual(old,self.folder());self.assertTrue(old.is_dir())
    def test_file_and_byte_budget_are_errors_not_partial_success(self):
        with patch.object(mod,'MAX_FILES',1):self.assertEqual(self.prepare(),1)
        with patch.object(mod,'MAX_BYTES',1):self.assertEqual(self.prepare(),1)
    def test_legacy_committed_receipt_is_not_authority(self):
        folder=self.dsh/'.dsha-rc1-migration';folder.mkdir();(folder/'receipt.json').write_text('{"version":1,"status":"committed","sourcePreserved":true}')
        self.prepare();self.assertEqual(self.doc()['version'],2);self.assertTrue((self.folder()/'legacy-receipt.json').exists())
    def test_preset_source_and_candidate_remain_independent(self):
        preset=self.dsh/'.agent-presets/crew';preset.mkdir(parents=True);(preset/'agent.cordis.yml').write_text('- id: old\n')
        self.prepare();candidate=self.dsh/self.doc()['presets'][0]['candidate'];self.assertFalse((candidate/'bundle').exists());(preset/'agent.cordis.yml').write_text('changed')
        self.assertIsNone(self.doc()['presets'][0]['bundle']);self.assertFalse(self.doc()['presets'][0]['activated'])
        self.assertEqual((candidate/'agent.cordis.yml').read_text(),'- id: old\n')
        code,payload=self.capture(self.finalize);self.assertEqual(code,0)
        self.assertIn('SOURCE_CHANGED:.agent-presets/crew/agent.cordis.yml',payload['warnings'])
        self.assertEqual(payload['contentCopyStatus'],'verified')
    def test_preset_yaml_remains_raw_for_explicit_native_ast_conversion(self):
        preset=self.dsh/'.agent-presets/crew';preset.mkdir(parents=True)
        original='---\n# preserved comment\n- id: old\n  name: plugin\n  config: &shared\n    prompt: |\n      user: prompt\n- id: next\n  name: plugin\n  config: *shared\n'
        (preset/'agent.cordis.yml').write_text(original,encoding='utf8');self.assertEqual(self.prepare(),0)
        candidate=self.dsh/self.doc()['presets'][0]['candidate']
        self.assertEqual((candidate/'agent.cordis.yml').read_text(encoding='utf8'),original)
        self.assertFalse((candidate/'bundle').exists());self.assertEqual((preset/'agent.cordis.yml').read_text(encoding='utf8'),original)
    def test_preset_root_metadata_is_snapshotted_without_becoming_directory_candidate(self):
        presets=self.dsh/'.agent-presets';presets.mkdir();(presets/'index.json').write_text('{"selected":"crew"}')
        (presets/'notes').write_text('keep this unknown root file')
        crew=presets/'crew';crew.mkdir();(crew/'agent.cordis.yml').write_text('- id: old\n')
        self.assertEqual(self.prepare(),0)
        doc=self.doc();self.assertEqual(len(doc['presets']),1);self.assertEqual(doc['presets'][0]['id'],'crew')
        rows={row['path']:row for row in doc['sources']}
        for name,value in [('index.json','{"selected":"crew"}'),('notes','keep this unknown root file')]:
            self.assertEqual((self.folder()/rows['.agent-presets/'+name]['snapshot']).read_text(),value)
            self.assertEqual((presets/name).read_text(),value)
            self.assertIn('PRESET_ROOT_FILE_PRESERVED:.agent-presets/'+name,doc['warnings'])
        self.assertEqual(len(list((self.folder()/'snapshots').iterdir())),len(doc['sources']))
    def test_post_snapshot_copy_failure_records_exact_operation_and_path(self):
        crew=self.dsh/'.agent-presets/crew';crew.mkdir(parents=True);(crew/'agent.cordis.yml').write_text('- id: old\n')
        with patch.object(mod.shutil,'copyfile',side_effect=IsADirectoryError(21,'is a directory')):
            self.assertEqual(self.prepare(),1)
        folder=next((self.state/'generations').iterdir());doc=json.loads((folder/'prepare.json').read_text())
        self.assertEqual(doc['failure']['operation'],'preset-candidate-copy')
        self.assertEqual(doc['failure']['path'],'.agent-presets/crew/agent.cordis.yml')
        self.assertIn('IsADirectoryError',doc['failure']['traceback'])
        self.assertFalse(doc['protectionComplete']);self.assertFalse((self.state/'current.json').exists())
        self.assertEqual(len(list((folder/'snapshots').iterdir())),len(doc['sources']))
        self.assertEqual((crew/'agent.cordis.yml').read_text(),'- id: old\n')
    def test_directory_settings_has_typed_source_error(self):
        settings=self.dsh/'settings.yaml';settings.unlink();settings.mkdir();(settings/'keep').write_text('original')
        with self.assertRaises(mod.MigrationSourceError) as raised:self.prepare()
        self.assertEqual(str(raised.exception),'MIGRATION_SOURCE_NOT_REGULAR')
        self.assertEqual(raised.exception.operation,'settings-input')
        self.assertEqual(raised.exception.logical,str(settings))
        self.assertFalse((self.state/'current.json').exists());self.assertEqual((settings/'keep').read_text(),'original')
    def test_directory_never_passes_snapshot_source_guard(self):
        folder=self.root/'candidate';folder.mkdir();directory=self.dsh/'directory';directory.mkdir()
        with self.assertRaisesRegex(mod.MigrationSourceError,'SOURCE_NOT_REGULAR'):
            mod.snapshot_row(folder,self.dsh,directory,directory,[],'preset',0)
        self.assertFalse((folder/'snapshots/0').exists())
    def test_stale_finalize_cannot_commit_new_startup(self):
        self.prepare();self.assertEqual(mod.finalize(str(self.root),str(self.state),'old-boot'),1);self.assertFalse((self.folder()/'receipt.json').exists())
    def test_prepared_wire_is_current_and_has_complete_minimal_fields(self):
        code,payload=self.capture(self.prepare)
        self.assertEqual(code,0)
        self.assertEqual({key:payload[key] for key in ('resultVersion','phase','status','startupId','verificationScope','contentCopyStatus','sourceObjectIdentity','settingsImportStatus','executionState')},
            dict(resultVersion=3,phase='prepare',status='prepared',startupId='boot',verificationScope='current',contentCopyStatus='verified',sourceObjectIdentity='unknown',settingsImportStatus='pending',executionState='COMPLETED'))
        self.assertEqual(payload['generation'],self.doc()['generation'])
        self.assertEqual(self.current()['startupContext'],mod.startup_context(payload['generation'],'boot',self.doc()['inputDigest'],mod.settings_sha256(self.doc()),'write-allowed'))
    def test_history_prepare_and_finalize_do_not_rescan_or_rewrite_business_records(self):
        self.prepare();self.verified();self.finalize()
        before={str(p):p.read_bytes() for p in self.state.rglob('*') if p.is_file() and p.name!='current.json'}
        with patch.object(mod.Resolver,'files',side_effect=AssertionError('history must not enumerate')),patch.object(mod,'snapshot_row',side_effect=AssertionError('history must not copy')):
            for fn in (self.prepare,self.finalize):
                code,payload=self.capture(fn);self.assertEqual(code,0)
                self.assertEqual(payload['status'],'historical-complete');self.assertEqual(payload['verificationScope'],'historical')
                self.assertEqual(payload['settingsImportStatus'],'verified-historical');self.assertEqual(payload['executionState'],'NOT_SENT')
                self.assertNotIn('sourcePreserved',payload)
        self.assertEqual(self.current()['startupContext']['mode'],'history-only')
        for name,value in before.items():self.assertEqual(value,Path(name).read_bytes())
    def test_history_only_missing_receipt_never_creates_one(self):
        self.prepare();self.verified();self.prepare()
        with patch.object(mod,'digest',side_effect=AssertionError('no historical copy/source hashing')):
            code,payload=self.capture(self.finalize)
        self.assertEqual(code,0);self.assertEqual(payload['status'],'pending')
        self.assertEqual(payload['executionState'],'NOT_SENT');self.assertFalse((self.folder()/'receipt.json').exists())
        self.assertFalse((self.folder()/'reuse.json').exists())
    def test_missing_ledger_after_commit_is_unknown_and_never_repairs_it(self):
        self.prepare();self.verified();self.finalize();(self.folder()/'settings-result.json').unlink()
        receipt=(self.folder()/'receipt.json').read_bytes();checkpoint=(self.folder()/'reuse.json').read_bytes()
        for fn in (self.prepare,self.finalize):
            code,payload=self.capture(fn);self.assertEqual(code,0)
            self.assertEqual(payload['status'],'unknown');self.assertEqual(payload['settingsImportStatus'],'unknown')
            self.assertIn('SETTINGS_LEDGER_MISSING',payload['warnings'])
        self.assertEqual(receipt,(self.folder()/'receipt.json').read_bytes());self.assertEqual(checkpoint,(self.folder()/'reuse.json').read_bytes())
        self.assertFalse((self.folder()/'settings-result.json').exists())
    def test_lost_or_broken_current_does_not_create_generation(self):
        self.prepare();self.verified();self.finalize();folders=list((self.state/'generations').iterdir())
        for bad in (None,'{broken','[]','{}'):
            if (self.state/'current.json').exists():(self.state/'current.json').unlink()
            if bad is not None:(self.state/'current.json').write_text(bad)
            code,payload=self.capture(self.prepare);self.assertEqual(code,1);self.assertEqual(payload['status'],'unknown')
            self.assertEqual(folders,list((self.state/'generations').iterdir()))
    def test_finalize_rejects_all_startup_context_mismatches_before_writes(self):
        self.prepare();self.verified();current=self.current();path=self.state/'current.json'
        for key,value in (('version',2),('startupId','other'),('generation','other'),('inputFingerprint','b'*64),('settingsSha256','b'*64),('mode','invalid'),('proofDigest','b'*64)):
            changed=dict(current,startupContext=dict(current['startupContext'],**{key:value}));path.write_text(json.dumps(changed))
            with self.subTest(key=key),patch.object(mod,'write_json',side_effect=AssertionError('invalid context must not write')):
                code,payload=self.capture(self.finalize)
                self.assertEqual(code,1);self.assertEqual(payload['reason'],'STALE_STARTUP_BINDING')
        path.write_text(json.dumps(current));self.assertFalse((self.folder()/'receipt.json').exists())
    def test_expected_generation_is_checked_before_any_finalize_write(self):
        self.prepare();self.verified()
        with patch.object(mod,'write_json',side_effect=AssertionError('wrong generation must not write')):
            code,payload=self.capture(mod.finalize,str(self.root),str(self.state),'boot',(),'ffffffff-ffff-ffff-ffff-ffffffffffff')
        self.assertEqual(code,1);self.assertEqual(payload['reason'],'EXPECTED_GENERATION_MISMATCH')
        self.assertFalse((self.folder()/'receipt.json').exists());self.assertFalse((self.folder()/'reuse.json').exists())
    def test_cli_expected_generation_and_error_paths_emit_one_terminal(self):
        import subprocess, sys
        self.prepare();self.verified()
        args=[sys.executable,str(SCRIPT),'finalize','--root',str(self.root),'--state-root',str(self.state),'--startup-id','boot']
        completed=subprocess.run(args+['--expected-generation','ffffffff-ffff-ffff-ffff-ffffffffffff'],capture_output=True,text=True)
        self.assertEqual(completed.returncode,1,completed.stderr)
        lines=completed.stdout.splitlines();self.assertEqual(len(lines),1)
        payload=json.loads(lines[0].split('=',1)[1]);self.assertEqual(payload['reason'],'EXPECTED_GENERATION_MISMATCH')
        self.assertEqual(payload['startupId'],'boot');self.assertFalse((self.folder()/'receipt.json').exists())
        completed=subprocess.run(args+['--expected-generation',self.doc()['generation']],capture_output=True,text=True)
        self.assertEqual(completed.returncode,0,completed.stderr)
        self.assertEqual(len(completed.stdout.splitlines()),1)
        self.assertEqual(json.loads(completed.stdout.split('=',1)[1])['status'],'committed')
        (self.dsh/'runtime-patches.json').unlink()
        completed=subprocess.run(args,capture_output=True,text=True)
        self.assertEqual(completed.returncode,1);self.assertEqual(len(completed.stdout.splitlines()),1)
        payload=json.loads(completed.stdout.split('=',1)[1]);self.assertEqual(payload['status'],'unknown')
        self.assertEqual(payload['startupId'],'boot')
    def test_ledger_missing_set_cannot_self_certify_empty_or_partial_keys(self):
        self.prepare();self.verified();ledger=self.ledger();ledger.pop('expectedSections')
        for sections in ({},{'first':{'status':'verified'}}):
            self.write_ledger(dict(ledger,sections=sections))
            self.assertEqual(mod.ledger_state(self.folder(),self.doc()),('unknown','SETTINGS_EXPECTED_SECTIONS_MISSING'))
            code,payload=self.capture(self.finalize);self.assertEqual(code,0);self.assertEqual(payload['status'],'pending')
            self.assertFalse((self.folder()/'reuse.json').exists())
    def test_ledger_declared_sets_require_type_uniqueness_and_full_coverage(self):
        self.prepare();self.verified();ledger=self.ledger()
        for expected in (None,'first',{},['first','first'],[1],[''],['first','second'],[]):
            with self.subTest(expected=expected):
                self.write_ledger(dict(ledger,expectedSections=expected))
                self.assertEqual(mod.ledger_state(self.folder(),self.doc())[0],'unknown')
        self.write_ledger(dict(ledger,expectedSections=[],sections={}))
        self.assertEqual(mod.ledger_state(self.folder(),self.doc())[0],'verified')
    def test_ledger_binding_and_unknown_write_states_never_verify(self):
        self.prepare();self.verified();ledger=self.ledger()
        for field,value in (('version',1),('generation','other'),('sourceSha256',None),('inputDigest','b'*64),('status','writing'),('status','unknown'),('status','bogus'),('sections',{'first':{'status':'writing'}}),('sections',{'first':{'status':'unknown'}})):
            with self.subTest(field=field,value=value):
                self.write_ledger(dict(ledger,**{field:value}))
                self.assertEqual(mod.ledger_state(self.folder(),self.doc())[0],'unknown')
        for state in ('pending','conflict'):
            self.write_ledger(dict(ledger,status=state,sections={'first':{'status':state}}))
            self.assertEqual(mod.ledger_state(self.folder(),self.doc())[0],'pending')
    def test_checkpoint_fallback_is_bound_and_not_derived_from_actual_keys(self):
        self.prepare();self.verified();self.finalize();checkpoint=json.loads((self.folder()/'reuse.json').read_text())
        ledger=self.ledger();ledger.pop('expectedSections');self.write_ledger(ledger)
        self.assertEqual(mod.ledger_state(self.folder(),self.doc())[0],'verified')
        for field,value in (('version',1),('generation','other'),('settingsSha256','b'*64),('inputDigest','b'*64),('inputs',{}),('settingsSections',['first','first']),('settingsSections',['other'])):
            with self.subTest(field=field):
                (self.folder()/'reuse.json').write_text(json.dumps(dict(checkpoint,**{field:value})))
                self.assertEqual(mod.ledger_state(self.folder(),self.doc())[0],'unknown')
        (self.folder()/'reuse.json').unlink()
        self.assertEqual(mod.ledger_state(self.folder(),self.doc())[0],'unknown')
        mod.publish_reuse(self.folder(),self.doc(),self.current(),self.result(),self.runtime_version)
        self.assertFalse((self.folder()/'reuse.json').exists())
    def test_contradictory_declared_and_checkpoint_sets_are_unknown(self):
        self.prepare();self.verified();self.finalize();ledger=self.ledger()
        ledger['expectedSections']=[];ledger['sections']={};self.write_ledger(ledger)
        self.assertEqual(mod.ledger_state(self.folder(),self.doc()),('unknown','SETTINGS_EXPECTED_SECTIONS_CONFLICT'))
    def test_no_settings_not_required_but_invalid_settings_sha_unknown(self):
        (self.dsh/'settings.yaml').unlink();code,payload=self.capture(self.prepare)
        self.assertEqual(payload['settingsImportStatus'],'not-required')
        code,payload=self.capture(self.finalize);self.assertEqual(payload['status'],'committed');self.assertEqual(payload['settingsImportStatus'],'not-required')
        for value in (None,'invalid'):
            self.assertEqual(mod.ledger_state(self.folder(),dict(sources=[dict(kind='settings',sha256=value)]))[0],'unknown')
        self.assertEqual(mod.ledger_state(self.folder(),dict(sources=[],inputs={'settings.yaml':'a'*64}))[0],'unknown')
    def test_current_ledger_from_other_startup_is_only_historical_settings(self):
        self.prepare();self.verified();ledger=self.ledger();ledger['startupId']='other';self.write_ledger(ledger)
        code,payload=self.capture(self.finalize)
        self.assertEqual(code,0);self.assertEqual(payload['status'],'committed');self.assertEqual(payload['settingsImportStatus'],'verified-historical')
        self.assertEqual(payload['verificationScope'],'current');self.assertEqual(payload['executionState'],'COMPLETED')
    def test_warnings_are_bounded_and_reuse_repair_has_single_terminal(self):
        self.assertEqual(len(mod.bounded_warnings(list(map(str,range(32))))),32)
        values=mod.bounded_warnings(list(map(str,range(40))))
        self.assertEqual(len(values),32);self.assertEqual(values[-1],'WARNINGS_TRUNCATED')
        self.prepare();self.verified();self.finalize();(self.folder()/'reuse.json').write_text('{broken')
        code,payload=self.capture(self.finalize);self.assertEqual(code,0)
        self.assertEqual(payload['status'],'historical-complete');self.assertIn('REUSE_CHECKPOINT_FORMAT',payload['warnings'])
    def test_dsh_missing_has_dedicated_skipped_combination(self):
        with tempfile.TemporaryDirectory(prefix='dsha-rc1-empty-') as empty:
            code,payload=self.capture(mod.prepare,empty,None,'empty-boot')
        self.assertEqual(code,0);self.assertEqual(payload['status'],'skipped');self.assertNotIn('generation',payload)
        self.assertEqual(payload['executionState'],'NOT_SENT');self.assertEqual(payload['startupId'],'empty-boot')
    def link(self,target,link,directory=False):
        try:os.symlink(target,link,target_is_directory=directory)
        except OSError as error:self.skipTest('host cannot create symlink: '+str(error.winerror if hasattr(error,'winerror') else error.errno))
    def test_public_link_copies_bytes_and_rejects_escape(self):
        external=self.root/'approved';external.mkdir();source=external/'settings';source.write_text('llm: {model: linked}\n');(self.dsh/'settings.yaml').unlink();self.link(source,self.dsh/'settings.yaml')
        self.assertEqual(mod.prepare(str(self.root),str(self.state),'boot',[str(external)]),0);row=self.doc()['sources'][0];snapshot=self.folder()/row['snapshot'];self.assertFalse(snapshot.is_symlink());source.write_text('later');self.assertEqual(snapshot.read_text(),'llm: {model: linked}\n')
        with self.assertRaisesRegex(ValueError,'OUTSIDE'):mod.Resolver(self.dsh).resolve(self.dsh/'settings.yaml')
    def test_linked_session_root_is_traversed(self):
        external=self.root/'approved';external.mkdir();(external/'session.v2.jsonl').write_text('old');(self.dsh/'sessions/session.v3.jsonl').unlink();(self.dsh/'sessions').rmdir();self.link(external,self.dsh/'sessions',True)
        mod.prepare(str(self.root),str(self.state),'boot',[str(external)]);self.assertEqual(len(self.doc()['sessions']),1)

if __name__=='__main__':unittest.main()
