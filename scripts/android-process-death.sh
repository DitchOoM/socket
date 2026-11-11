# shellcheck shell=bash
# Sourced by scripts/android-emulator-tests.sh.

# Prints one line per process the saved logcat shows dying, naming its package, how it died, and the
# last test it started. A native crash (libc `Fatal signal`, debuggerd's `Abort message`) is named
# with its signal, thread and abort message; a death with no native crash recorded says so.
# Argument: the logcat file (threadtime format).
named_process_deaths() {
  awk '
    function note(pid) { if (!(pid in seen)) { seen[pid] = 1; order[++n] = pid } }
    $6 == "TestRunner:" && $7 == "started:" { last[$3] = $8 }
    / F libc +: Fatal signal / {
      sig[$3] = $0; sub(/.*Fatal signal [0-9]+ \(/, "", sig[$3]); sub(/\).*/, "", sig[$3])
      thr[$3] = $0; sub(/.* in tid [0-9]+ \(/, "", thr[$3]); sub(/\)$/, "", thr[$3])
      note($3)
    }
    / F DEBUG +: pid: [0-9]+, tid: / {
      cur = $0; sub(/.*DEBUG +: pid: /, "", cur); sub(/,.*/, "", cur)
      pkg[cur] = $0; sub(/.*>>> /, "", pkg[cur]); sub(/ <<<.*/, "", pkg[cur])
    }
    / F DEBUG +: Abort message: / && cur != "" {
      msg[cur] = $0; sub(/.*Abort message: /, "", msg[cur]); gsub(/^.|.$/, "", msg[cur])
    }
    / ActivityManager: Process .* \(pid [0-9]+\) has died/ {
      p = $0; sub(/.*\(pid /, "", p); sub(/\).*/, "", p)
      name = $0; sub(/.*ActivityManager: Process /, "", name); sub(/ \(pid.*/, "", name)
      if (!(p in pkg)) pkg[p] = name
      note(p)
    }
    END {
      for (i = 1; i <= n; i++) {
        p = order[i]
        who = (p in pkg) ? pkg[p] : "process"
        if (p in sig) {
          how = "crashed with " sig[p] " on thread " thr[p] " (pid " p ")"
          if (p in msg) how = how "; abort message: " msg[p]
        } else {
          how = "died with no native crash recorded (pid " p ")"
        }
        print who " " how "; last test started: " ((p in last) ? last[p] : "none")
      }
    }
  ' "$1" 2>/dev/null
}
