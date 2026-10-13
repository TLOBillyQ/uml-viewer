"""Public Windows wrapper restart boundary, using real JVMs and controlled processes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

REPO = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.name == 'nt' and shutil.which('clojure.exe'), 'native Windows clojure.exe required')
class RestartEntries(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='uml restart ')
        self.root = Path(self.temp.name)
        self.project = self.root / 'project A'
        self.project.mkdir()
        self.installed = self.project / '.uml-viewer/uml-viewer'
        self.installed.mkdir(parents=True)
        (self.installed / 'src/uml_viewer/main').mkdir(parents=True)
        shutil.copytree(REPO / 'scripts', self.installed / 'scripts')
        shutil.copy(REPO / 'scripts/uml.ps1', self.project / 'uml.ps1')
        (self.installed / 'deps.edn').write_text('{:paths ["src"]}')
        (self.installed / 'src/uml_viewer/main/uml_viewer.clj').write_text((REPO / 'src/uml_viewer/main/uml_viewer.clj').read_text())
        self.source = self.project / 'src'
        # Backend process boundary is controlled before the viewer main starts.
        (self.installed / 'src/uml_viewer/adapters').mkdir(parents=True)
        (self.installed / 'src/uml_viewer/adapters/companion.clj').write_text(
            '(ns uml-viewer.adapters.companion) (defn -main [& _] (println "herdr"))')
        (self.installed / 'src/uml_viewer/main/uml_viewer.clj').write_text('''
(ns uml-viewer.main.uml-viewer
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))
(defn -main [& args]
  ;; Production identity record seam, using ProcessHandle and the launch env root.
  (let [p (java.lang.ProcessHandle/current)
        root (System/getenv "UML_VIEWER_PROJECT_ROOT")]
    (.mkdirs (io/file root ".uml-viewer"))
    (spit (io/file root ".uml-viewer/viewer-jvm.json")
          (json/write-str {:pid (.pid p) :started (str (.get (.startInstant (.info p))))
                           :cwd (.getCanonicalPath (io/file root))}))
    (spit (str root "/observed.json")
          (str "{\\"pid\\":" (.pid p) ",\\"argv\\":[" (str/join "," (map #(str "\\"" % "\\"") args)) "]}"))
    (while (not (.exists (java.io.File. root "release"))) (Thread/sleep 20))
    (shutdown-agents)
    (System/exit 0)))
''')
        (self.project / 'deps.edn').write_text(
            '{:paths ["src"] :deps {org.clojure/data.json {:mvn/version "2.5.1"}}}')
        self.children = []
        self.controlled_jvm = None

    def live_jvm_with_dead_launcher(self):
        # Keep the production ownership parser; control only external Herdr probes.
        (self.installed / 'src/uml_viewer/domain').mkdir(parents=True)
        shutil.copy(REPO / 'src/uml_viewer/domain/mailbox.clj',
                    self.installed / 'src/uml_viewer/domain/mailbox.clj')
        adapter = (REPO / 'src/uml_viewer/adapters/companion.clj').read_text(encoding='utf-8')
        self.assertEqual(1, adapter.count('os process!'))
        probes = '''(defn fixture-run [argv]
  (cond
    (= argv ["herdr" "--version"]) {:exit 0 :out "herdr 0.9.3"}
    (= argv ["herdr" "api" "schema" "--json"]) {:exit 0 :out "{\\"$schema\\":\\"fixture\\",\\"protocol\\":22,\\"fixture\\":true}"}
    :else (throw (ex-info "Unexpected fixture probe" {:argv argv}))))

'''
        adapter = adapter.replace('(defn -main', probes + '(defn -main').replace('os process!', 'os fixture-run')
        (self.installed / 'src/uml_viewer/adapters/companion.clj').write_text(adapter, encoding='utf-8')
        directory = self.project / '.uml-viewer'
        (directory / 'companion.edn').write_text(
            '{:backend :herdr :socket "isolated-fixture" :workspace "isolated-fixture" '
            ':terminal-id "isolated-fixture" :shell-pid 2147483647 :owner-start "fixture" '
            ':server-pid 2147483647 :server-start "fixture" :session "isolated-fixture" '
            ':pane "isolated-fixture" :owner-role :companion :cwd ' +
            json.dumps(str(self.project.resolve()).replace('\\', '/')) + '}', encoding='utf-8')
        (directory / 'viewer-process.json').write_text(json.dumps(
            {'pid': 2147483647, 'started': '1', 'token': 'isolated-dead-launcher'}))
        writer = self.root / 'IdentityWriter.java'
        writer.write_text('''import java.nio.file.*;
public class IdentityWriter {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    ProcessHandle p = ProcessHandle.current();
    Files.writeString(root.resolve(".uml-viewer/viewer-jvm.json"),
      "{\\"pid\\":" + p.pid() + ",\\"started\\":\\"" +
      p.info().startInstant().orElseThrow().toString() + "\\"}");
    while (!Files.exists(root.resolve("release"))) Thread.sleep(20);
  }
}''', encoding='utf-8')
        self.controlled_jvm = subprocess.Popen(['java', str(writer), str(self.project)],
                                              cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                              text=True, creationflags=subprocess.CREATE_NO_WINDOW)
        record = self.await_json(directory / 'viewer-jvm.json')
        probe = subprocess.run(['pwsh', '-NoProfile', '-Command',
            f'$p = Get-Process -Id {record["pid"]}; @{{started = $p.StartTime.ToUniversalTime().ToString("o"); '
            'ticks = $p.StartTime.ToUniversalTime().Ticks.ToString(); '
            'deadLauncherAbsent = ($null -eq (Get-Process -Id 2147483647 -ErrorAction SilentlyContinue))} | ConvertTo-Json'],
            capture_output=True, text=True, check=True, creationflags=subprocess.CREATE_NO_WINDOW)
        observed = json.loads(probe.stdout)
        self.assertTrue(observed['deadLauncherAbsent'])
        self.assertIsNone(self.controlled_jvm.poll())
        return record, observed

    def tearDown(self):
        (self.project / 'release').touch()
        if hasattr(self, 'original_jvm_record'):
            (self.project / '.uml-viewer/viewer-jvm.json').write_text(json.dumps(self.original_jvm_record))
        deadline = time.monotonic() + 40
        try:
            if self.controlled_jvm is not None:
                out, err = self.controlled_jvm.communicate(timeout=20)
                self.assertEqual(0, self.controlled_jvm.returncode, err)
            # A detached launcher inherits this test's console through conhost;
            # wait until the observed fixture JVM and launcher are gone instead of
            # letting Windows block process deletion in TemporaryDirectory cleanup.
            while time.monotonic() < deadline:
                try:
                    jvm = json.loads((self.project / '.uml-viewer/viewer-jvm.json').read_text(encoding='utf-8-sig'))
                    launcher = json.loads((self.project / '.uml-viewer/viewer-process.json').read_text(encoding='utf-8-sig'))
                except (FileNotFoundError, json.JSONDecodeError):
                    break
                live = []
                for pid in [jvm.get('pid'), launcher.get('pid')]:
                    if pid is None:
                        continue
                    check = subprocess.run(['pwsh', '-NoProfile', '-Command', f'Get-Process -Id {pid} -ErrorAction SilentlyContinue'],
                                           capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
                    if check.stdout:
                        live.append(pid)
                if not live:
                    break
                time.sleep(.1)
        finally:
            self.temp.cleanup()

    def entry(self, *argv):
        # Never use subprocess.run(capture_output=True): the detached launcher
        # inherits its pipes and would hold them open for the whole JVM lifetime.
        with (self.project / 'entry-error.txt').open('w') as error_file:
            process = subprocess.Popen(['pwsh', '-NoProfile', '-File', str(self.project / 'uml.ps1'), *argv],
                                       cwd=self.root, stdout=subprocess.DEVNULL, stderr=error_file,
                                       text=True, creationflags=subprocess.CREATE_NO_WINDOW)
            process.wait(timeout=60)
        process.stdout and process.stdout.close()
        process.stderr and process.stderr.close()
        error_file = self.project / 'entry-error.txt'
        return subprocess.CompletedProcess(process.args, process.returncode, None,
                                           error_file.read_text(errors='replace')
                                           if error_file.exists() else '')

    def await_json(self, path, previous_pid=None):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                value = json.loads(path.read_text(encoding='utf-8-sig'))
                if previous_pid is None or value['pid'] != previous_pid:
                    return value
            except (FileNotFoundError, json.JSONDecodeError):
                pass
            time.sleep(.05)
        self.fail(f'No new process evidence at {path}')

    def test_restart_waits_thirty_seconds_for_live_java_identity_without_replacement(self):
        recorded, actual = self.live_jvm_with_dead_launcher()
        # Independent runtime evidence: Java Windows drops the sub-millisecond
        # FILETIME part; .NET exposes it. Neither runtime's string is synthesized.
        self.assertNotEqual(recorded['started'], actual['started'])
        self.assertNotEqual(0, int(actual['ticks']) % 10000,
                            'fixture must exercise a real sub-millisecond difference')
        started = time.monotonic()
        result = self.entry('--restart')
        elapsed = time.monotonic() - started
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Old viewer JVM is still running', result.stderr, (recorded, actual))
        self.assertIn(':quit-for-restart', result.stderr)
        self.assertGreaterEqual(elapsed, 30)
        self.assertLess(elapsed, 55)
        self.assertIsNone(self.controlled_jvm.poll())
        self.assertFalse(list((self.project / '.uml-viewer').glob('launch-*.json')))

    def test_restart_continues_after_live_java_exits_without_changing_saved_state(self):
        old, _ = self.live_jvm_with_dead_launcher()
        directory = self.project / '.uml-viewer'
        saved = {
            'companion.edn': (directory / 'companion.edn').read_bytes(),
            'session.edn': b'{:path "saved.edn" :context {:context :proposal :proposal-id "p"} :view {:zoom 2} :mailbox-cursor 17}',
            'to-companion.edn': b'{:next-id 19 :queue [{:id 18 :context :real}]}',
        }
        for name, value in saved.items():
            (directory / name).write_bytes(value)
        output = self.project / 'restart-output.txt'
        error = self.project / 'restart-error.txt'
        with output.open('w') as stdout, error.open('w') as stderr:
            waiter = subprocess.Popen(['pwsh', '-NoProfile', '-File', str(self.project / 'uml.ps1'), '--restart'],
                                      cwd=self.root, stdout=stdout, stderr=stderr,
                                      creationflags=subprocess.CREATE_NO_WINDOW)
            self.children.append(waiter)
            try:
                deadline = time.monotonic() + 20
                marker = 'Waiting for the old viewer JVM after :quit-for-restart...'
                while marker not in output.read_text(errors='replace'):
                    self.assertIsNone(waiter.poll(), error.read_text(errors='replace'))
                    self.assertIsNone(self.controlled_jvm.poll(), 'old JVM exited before waiting')
                    if time.monotonic() >= deadline:
                        self.fail('public restart did not report waiting for the live JVM')
                    time.sleep(.05)
                self.assertIsNone(waiter.poll())
                self.assertIsNone(self.controlled_jvm.poll())
                # Only release once the public wrapper confirms it is waiting.
                (self.project / 'release').touch()
                waiter.wait(timeout=60)
            finally:
                (self.project / 'release').touch()
                waiter.wait(timeout=60)
                if waiter.returncode == 0:
                    self.await_json(self.project / 'observed.json', old['pid'])
        self.assertEqual(0, waiter.returncode, error.read_text(errors='replace'))
        new = self.await_json(self.project / 'observed.json', old['pid'])
        self.assertEqual(['--backend', 'herdr', '--restart'], new['argv'])
        self.controlled_jvm.wait(timeout=20)
        self.assertEqual(0, self.controlled_jvm.returncode)
        for name, value in saved.items():
            self.assertEqual(value, (directory / name).read_bytes(), name)

    def test_restart_rejects_unconfirmed_live_jvm_identity_without_replacement(self):
        record, actual = self.live_jvm_with_dead_launcher()
        path = self.project / '.uml-viewer/viewer-jvm.json'
        # Finer timestamps in the same millisecond are not covered by the
        # millisecond writer contract. A one-microsecond disagreement must fail.
        precise_wrong = actual['started'][:23] + ('001' if actual['started'][23:26] != '001' else '002') + 'Z'
        python_start = subprocess.run(['pwsh', '-NoProfile', '-Command',
            f'(Get-Process -Id {os.getpid()}).StartTime.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")'],
            capture_output=True, text=True, check=True).stdout.strip()
        self.original_jvm_record = record
        cases = [
            ('changed second', {**record, 'started': '2000-01-01T00:00:00.000Z'}),
            ('same millisecond different precise instant', {**record, 'started': precise_wrong}),
            ('malformed', {**record, 'started': 'garbage'}),
            ('date only', {**record, 'started': '2026-10-13'}),
            ('missing UTC', {**record, 'started': record['started'][:-1]}),
            ('missing start', {'pid': record['pid']}),
            ('wrong name', {**record, 'pid': os.getpid(), 'started': python_start}),
        ]
        for name, invalid in cases:
            with self.subTest(name=name):
                path.write_text(json.dumps(invalid))
                result = self.entry('--restart')
                self.assertNotEqual(0, result.returncode, result.stderr)
                self.assertIn('cannot safely restart', result.stderr)
                self.assertNotIn('still running', result.stderr)
                self.assertIsNone(self.controlled_jvm.poll())
                self.assertFalse(list((self.project / '.uml-viewer').glob('launch-*.json')))

    def test_restart_preserves_omitted_path_and_waits_for_authoritative_jvm(self):
        first = self.entry('chosen with spaces.edn')
        self.assertEqual(0, first.returncode, first.stderr)
        old = self.await_json(self.project / 'observed.json')
        (self.project / '.uml-viewer/viewer-jvm.json').rename(self.project / 'first-viewer-jvm.json')
        with (self.project / 'restart-error.txt').open('w') as restart_error:
            self.children.append(subprocess.Popen(['pwsh', '-NoProfile', '-File', str(self.project / 'uml.ps1'), '--restart'],
                                  cwd=self.root, stdout=subprocess.DEVNULL,
                                  stderr=restart_error, text=True,
                                  creationflags=subprocess.CREATE_NO_WINDOW))
        # Restart must wait for both exact recorded processes, not just a dead launcher.
        waiter = self.children[-1]
        shutil.copy(self.project / 'first-viewer-jvm.json', self.project / '.uml-viewer/viewer-jvm.json')
        time.sleep(2)
        self.assertIsNone(waiter.poll(), 'restart returned before old JVM exit')
        (self.project / 'release').touch()
        waiter.wait(timeout=60)
        self.assertEqual(0, waiter.returncode, (self.project / 'restart-error.txt').read_text(errors='replace'))
        new = self.await_json(self.project / 'observed.json', old['pid'])
        self.assertEqual(['--backend', 'herdr', '--restart'], new['argv'])
        gone = subprocess.run(['pwsh', '-NoProfile', '-Command', f'Get-Process -Id {old["pid"]} -ErrorAction SilentlyContinue'], capture_output=True)
        self.assertEqual(b'', gone.stdout)

    def test_restart_forwards_explicit_path_even_when_session_has_saved_path(self):
        first = self.entry('original.edn')
        self.assertEqual(0, first.returncode, first.stderr)
        old = self.await_json(self.project / 'observed.json')
        (self.project / '.uml-viewer/session.edn').write_text('{:path "saved.edn"}')
        (self.project / 'release').touch()
        result = self.entry('--restart', 'override with spaces.edn')
        self.assertEqual(0, result.returncode, result.stderr)
        new = self.await_json(self.project / 'observed.json', old['pid'])
        self.assertEqual(['--backend', 'herdr', '--restart', 'override with spaces.edn'], new['argv'])

    def test_restart_rejects_missing_jvm_record_without_launching_replacement(self):
        directory = self.project / '.uml-viewer'
        (directory / 'viewer-process.json').write_text(json.dumps({'pid': 2147483647, 'started': '1', 'token': 'old'}))
        result = self.entry('--restart')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('JVM', result.stderr)
        self.assertFalse((self.project / 'observed.json').exists())


if __name__ == '__main__':
    unittest.main()
