(ns uml-viewer.adapters.windows-companion-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.sketch :as sketch]))

(describe "Native Windows companion at the external process boundary"
  (it "discovers a configured PowerShell Claude entry with spaces at the process boundary"
    (with-redefs [companion/windows? (fn [] true)
                  companion/process! (fn [args]
                                       (should= "pwsh.exe" (first args))
                                       {:exit 0 :out "C:\\With Spaces\\claude.ps1"})]
      (should= "C:\\With Spaces\\claude.ps1" (sketch/claude-executable))))
  (it "removes the listed variables from the real child process environment"
    (let [result (companion/process! ["pwsh" "-NoProfile" "-Command" "[Console]::Write('U=' + [string]$env:USERNAME)"]
                                     10000 ["USERNAME"])]
      (should= 0 (:exit result))
      (should= "U=" (:out result)))))
