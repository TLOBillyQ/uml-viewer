"""Optional quality dispatch acceptance through the installed public entries."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]


class QualityConfigEntries(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='quality project & spaces ')
        self.root = Path(self.tmp.name)
        self.project = self.root / 'sample project'
        self.project.mkdir()
        scripts = self.project / '.uml-viewer/uml-viewer/scripts'
        scripts.mkdir(parents=True)
        for path in (REPO / 'scripts').glob('uml*'):
            if path.is_file():
                shutil.copyfile(path, scripts / path.name)
        for name in ['uml', 'uml.ps1']:
            shutil.copyfile(REPO / 'scripts' / name, self.project / name)
        (self.project / 'uml').chmod(0o755)
        (self.project / 'deps.edn').write_text('{}')
        self.fixture = self.project / 'controlled command.py'
        self.fixture.write_text('import json, os, sys\nprint(json.dumps({"cwd": os.getcwd(), "argv": sys.argv[1:]}))\nprint("controlled stderr", file=sys.stderr)\nsys.exit(int(os.environ.get("QUALITY_EXIT", "0")))\n')

    def tearDown(self):
        self.tmp.cleanup()

    def configure(self, forwarding='append', scope='targets', codes=None):
        import sys
        operation = {'argv': [sys.executable, '-I', str(self.fixture), 'fixed value'],
                     'forwardArgs': forwarding, 'validResultExitCodes': codes or [0]}
        config = {'version': 1, 'operations': {'crap': dict(operation, scope=scope),
                                              'mutate': operation}}
        (self.project / 'uml-quality.json').write_text(json.dumps(config))
        return config

    def run_entry(self, operation='crap', args=None, code=0, unix=False):
        entry = ([str(self.project / 'uml')] if unix else
                 ['pwsh', '-NoProfile', '-File', str(self.project / 'uml.ps1')])
        return subprocess.run(entry + [operation] + (args or []), cwd=self.root,
                              env=dict(os.environ, QUALITY_EXIT=str(code)), text=True, capture_output=True)

    def test_relative_powershell_command_keeps_arguments_and_project_cwd(self):
        config = self.configure()
        script = self.project / 'quality command.ps1'
        script.write_text('[Console]::Out.WriteLine((ConvertTo-Json -Compress -InputObject @{cwd=(Get-Location).Path;argv=@($args)})); [Console]::Error.WriteLine("ps stderr"); exit 23\n')
        config['operations']['mutate']['argv'] = ['./quality command.ps1']
        (self.project / 'uml-quality.json').write_text(json.dumps(config))
        args = ['space value', 'literal;$(throw 1)', '', 'quoted "value"']
        result = self.run_entry('mutate', args)
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual({'cwd': str(self.project), 'argv': args}, json.loads(result.stdout))
        self.assertIn('ps stderr', result.stderr)

    def test_invalid_configuration_or_launch_never_reuses_prior_valid_result(self):
        config = self.configure()
        self.assertEqual(0, self.run_entry().returncode)
        path = self.project / 'uml-quality.json'
        path.write_text('{broken')
        result = self.run_entry()
        self.assertEqual(1, result.returncode)
        self.assertIn('uml-quality.json', result.stderr)
        self.assertFalse((self.project / '.uml-viewer/quality-result.json').exists())
        for invalid in [dict(config, operations={'crap': config['operations']['crap']}),
                        dict(config, version=2), dict(config, version=True), dict(config, version='1'),
                        {'version': 1, 'operations': {'crap': {}, 'mutate': {}}}]:
            path.write_text(json.dumps(invalid))
            result = self.run_entry()
            self.assertEqual(1, result.returncode)
            self.assertIn('uml-quality.json', result.stderr)
            self.assertEqual('', result.stdout)
        config['operations']['crap']['argv'] = ['missing-quality-executable-26']
        path.write_text(json.dumps(config))
        result = self.run_entry()
        self.assertEqual(1, result.returncode)
        self.assertFalse((self.project / '.uml-viewer/quality-result.json').exists())

    def test_crap_scope_selects_whole_tree_or_requested_targets(self):
        for scope, forwarding, expected in [('whole-tree', 'none', ['fixed value']),
                                             ('targets', 'append', ['fixed value', 'src/one.lua'])]:
            self.configure(scope=scope, forwarding=forwarding)
            result = self.run_entry(args=['src/one.lua'])
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(expected, json.loads(result.stdout)['argv'])
            record = json.loads((self.project / '.uml-viewer/quality-result.json').read_text())
            self.assertEqual(scope, record['crapScope'])

    def test_result_contract_distinguishes_declared_findings_from_failure(self):
        for codes, valid in [([0], False), ([0, 3], True)]:
            self.configure(codes=codes)
            result = self.run_entry('mutate', code=3)
            self.assertEqual(3, result.returncode, result.stderr)
            record = json.loads((self.project / '.uml-viewer/quality-result.json').read_text())
            self.assertEqual({'version': 1, 'operation': 'mutate', 'exitCode': 3,
                              'validResult': valid, 'crapScope': None, 'configured': True,
                              'tool': 'configured', 'projectRoot': str(self.project)}, record)

    def test_configured_command_precedes_alias_and_preserves_process_contract(self):
        self.configure(codes=[0, 3])
        args = ['src/file with spaces.lua', 'literal;$(throw 1)&$value', 'quoted "value"', '', 'first\nsecond']
        entries = [False, True] if os.name != 'nt' else [False]
        for unix in entries:
            result = self.run_entry('mutate', args, code=3, unix=unix)
            self.assertEqual(3, result.returncode, result.stderr)
            self.assertEqual({'cwd': str(self.project), 'argv': ['fixed value'] + args}, json.loads(result.stdout))
            self.assertIn('controlled stderr', result.stderr)


if __name__ == '__main__':
    unittest.main()
