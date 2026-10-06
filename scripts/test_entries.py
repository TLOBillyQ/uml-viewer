"""Real entry acceptance; all projects and repositories stay in the job tmp root."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
TMP = Path(os.environ.get('UML_ENTRY_TEST_TMP', str(Path(tempfile.gettempdir()) / 'uml-entry-tests')))


class Entries(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix='project with spaces ', dir=TMP))
        self.project = self.root / 'sample project'
        self.project.mkdir()
        self.source = self.root / 'viewer source'
        shutil.copytree(REPO, self.source, ignore=shutil.ignore_patterns('.git', '.claude'))
        subprocess.run(['git', 'init', '-b', 'test', str(self.source)], check=True, capture_output=True)
        subprocess.run(['git', '-C', str(self.source), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.source), '-c', 'user.name=Test', '-c', 'user.email=test@example.org', 'commit', '-m', 'fixture'], check=True, capture_output=True)
        self.env = dict(os.environ, UML_VIEWER_REPO_URL=str(self.source), UML_VIEWER_REF='test')
        (self.project / 'deps.edn').write_text('{:paths ["src"]}')
        (self.project / 'policy.edn').write_text('{:lang :clojure :src "src" :prefix "sample" :proposals [{:id :saved :name "Saved" :layers []}]}')
        (self.project / 'src').mkdir()
        (self.project / 'src/sample.clj').write_text('(ns sample)\n(defn greet [] "hello")\n')

    def run_entry(self, args, cwd=None, env=None):
        return subprocess.run(args, cwd=cwd or self.project, env=env or self.env, text=True, capture_output=True)

    def install(self):
        return self.run_entry(['pwsh', '-NoProfile', '-File', str(REPO / 'scripts/get-uml-viewer.ps1'), '--install-only'])

    def test_install_only_preserves_policy_and_generates_entries_on_repeat(self):
        policy = (self.project / 'policy.edn').read_bytes()
        (self.project / '.gitignore').touch()
        for _ in range(2):
            result = self.install()
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            for name in ['uml', 'uml.ps1', 'uml.cmd']:
                self.assertTrue((self.project / name).is_file(), name)
            self.assertEqual(policy, (self.project / 'policy.edn').read_bytes())
            self.assertFalse((self.project / 'uml-viewer-log.txt').exists())
        self.assertEqual(1, (self.project / '.gitignore').read_text().count('# BEGIN UML-VIEWER'))

    def test_ir_uses_real_generator_from_another_working_directory(self):
        self.assertEqual(0, self.install().returncode)
        result = self.run_entry([str(self.project / 'uml'), 'ir', 'diagram with spaces.edn'], cwd=self.root)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        diagram = (self.project / 'diagram with spaces.edn').read_text()
        self.assertIn('sample', diagram)
        self.assertIn('Saved', diagram)

    def test_metric_alias_preserves_arguments_working_directory_and_exit(self):
        self.assertEqual(0, self.install().returncode)
        bin_dir = self.root / 'fake tools'
        bin_dir.mkdir()
        cli = bin_dir / 'clojure'
        cli.write_text('#!/bin/sh\npwd > received.txt\nprintf "%s\\n" "$@" >> received.txt\nexit 23\n')
        cli.chmod(0o755)
        env = dict(self.env, PATH=str(bin_dir) + os.pathsep + self.env['PATH'])
        for command in ['crap', 'mutate']:
            result = self.run_entry([str(self.project / 'uml'), command, 'src/file with spaces.clj', '--mutate-all', 'literal;$value'], cwd=self.root, env=env)
            self.assertEqual(23, result.returncode, result.stderr)
            self.assertEqual([str(self.project), '-M:' + command, 'src/file with spaces.clj', '--mutate-all', 'literal;$value'], (self.project / 'received.txt').read_text().splitlines())

    def test_external_metrics_keep_project_cwd_and_differential_arguments(self):
        self.assertEqual(0, self.install().returncode)
        (self.project / 'deps.edn').unlink()
        for command, tool in [('crap', 'crapper'), ('mutate', 'mutator')]:
            tool_dir = self.project / '.uml-viewer' / tool
            tool_dir.mkdir()
            entry = tool_dir / tool
            entry.write_text('#!/bin/sh\npwd > external.txt\nprintf "%s\\n" "$@" >> external.txt\nexit 7\n')
            entry.chmod(0o755)
            result = self.run_entry([str(self.project / 'uml'), command, 'src/file with spaces.lua', 'literal;$value'], cwd=self.root)
            self.assertEqual(7, result.returncode, result.stderr)
            self.assertEqual([str(self.project), 'src/file with spaces.lua', 'literal;$value'], (self.project / 'external.txt').read_text().splitlines())

    def test_unix_installer_forwards_install_only_and_rejects_invalid_arguments(self):
        entry = str(REPO / 'scripts/get-uml-viewer')
        result = self.run_entry([entry, '--install-only'])
        self.assertEqual(0, result.returncode, result.stderr)
        result = self.run_entry([entry, '--install-only', 'extra'])
        self.assertNotEqual(0, result.returncode)
        self.assertIn('usage:', result.stderr)

    def test_missing_dependencies_and_policy_are_diagnostic(self):
        self.assertEqual(0, self.install().returncode)
        (self.project / 'policy.edn').unlink()
        result = self.run_entry([str(self.project / 'uml'), 'ir'])
        self.assertNotEqual(0, result.returncode)
        self.assertIn('no policy', result.stderr)
        (self.project / 'deps.edn').unlink()
        result = self.run_entry([str(self.project / 'uml'), 'mutate', 'file.lua'])
        self.assertNotEqual(0, result.returncode)
        self.assertIn('MUTATOR_REPO_URL', result.stderr)
        env = dict(self.env, PATH=str(self.root / 'empty path'))
        result = self.run_entry([str(self.project / 'uml'), 'crap'], env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('PowerShell 7', result.stderr)
        self.assertIn('https://aka.ms/powershell-install', result.stderr)
        pwsh = shutil.which('pwsh')
        (self.project / 'deps.edn').write_text('{}')
        result = self.run_entry([pwsh, '-NoProfile', '-File', str(self.project / 'uml.ps1'), 'crap'], env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Clojure CLI', result.stderr)
        result = self.run_entry([pwsh, '-NoProfile', '-File', str(REPO / 'scripts/get-uml-viewer.ps1'), '--install-only'], env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Install Git', result.stderr)

    def test_standalone_unix_bootstrap_uses_same_installer(self):
        standalone = self.root / 'downloaded installer'
        shutil.copyfile(REPO / 'scripts/get-uml-viewer', standalone)
        standalone.chmod(0o755)
        env = dict(self.env, UML_VIEWER_INSTALLER_URL=(REPO / 'scripts/get-uml-viewer.ps1').as_uri())
        result = self.run_entry([str(standalone), '--install-only'], env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.project / 'uml.ps1').exists())

    def test_install_without_deps_fetches_metric_checkouts_without_creating_policy(self):
        (self.project / 'deps.edn').unlink()
        (self.project / 'policy.edn').unlink()
        self.env.update(CRAPPER_REPO_URL=str(self.source), CRAPPER_REF='test', MUTATOR_REPO_URL=str(self.source), MUTATOR_REF='test')
        self.assertEqual(0, self.install().returncode)
        for tool in ['crapper', 'mutator']:
            self.assertTrue((self.project / '.uml-viewer' / tool / '.git').exists())
        self.assertFalse((self.project / 'policy.edn').exists())
        self.assertFalse((self.project / 'examples').exists())

    def test_windows_metric_process_boundary_uses_preinstalled_python_module(self):
        # Simulate the OS boundary only; invoke the generated PS1 and real fixture process.
        self.assertEqual(0, self.install().returncode)
        (self.project / 'deps.edn').unlink()
        runner = self.root / 'windows boundary.ps1'
        runner.write_text('Set-Variable IsWindows -Value $true -Force\n& $env:TEST_ENTRY @args\nexit $LASTEXITCODE\n')
        env = dict(self.env, TEST_ENTRY=str(self.project / 'uml.ps1'))
        result = self.run_entry(['pwsh', '-NoProfile', '-File', str(runner), 'mutate'], env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('py -3 -m venv', result.stderr)
        self.assertIn('pip install -e', result.stderr)
        for command, tool in [('crap', 'crapper'), ('mutate', 'mutator')]:
            directory = self.project / '.uml-viewer' / tool / '.venv/Scripts'
            directory.mkdir(parents=True)
            python = directory / 'python.exe'
            python.write_text('#!/bin/sh\npwd > windows.txt\nprintf "%s\\n" "$@" >> windows.txt\nexit 19\n')
            python.chmod(0o755)
            result = self.run_entry(['pwsh', '-NoProfile', '-File', str(runner), command, 'src/file with spaces.lua'], cwd=self.root, env=env)
            self.assertEqual(19, result.returncode, result.stderr)
            self.assertEqual([str(self.project), '-m', tool, 'src/file with spaces.lua'], (self.project / 'windows.txt').read_text().splitlines())


if __name__ == '__main__':
    unittest.main()
