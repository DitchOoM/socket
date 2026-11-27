#!/usr/bin/env bash
#
# Tests for runner-facts.sh: each host kind reports cores, memory, load and VM type from its own tools, a missing
# tool prints "unknown" instead of failing, and the facts are appended to the file given.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../runner-facts.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0
expect() { # <name> <got> <want>
  if [ "$2" = "$3" ]; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1: got '$2', want '$3'"; fail=$((fail + 1)); fi
}
contains() { # <name> <text> <needle>
  if grep -qF -- "$3" <<<"$2"; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1: '$3' not in: $2"; fail=$((fail + 1)); fi
}
stub() { # <dir> <name> <body>
  printf '#!/usr/bin/env bash\n%s\n' "$3" >"$1/$2"
  chmod +x "$1/$2"
}
# A PATH of the stubs plus only the basics the script itself needs, so the host's own tools cannot answer.
basics="$tmp/basics"
mkdir -p "$basics"
for tool in bash awk head date mkdir dirname cat grep sed sort sleep kill rm; do
  p="$(command -v "$tool" 2>/dev/null)" && ln -s "$p" "$basics/$tool"
done

# A macOS runner: the facts come from sysctl and vm_stat.
mac="$tmp/mac"
mkdir -p "$mac"
stub "$mac" uname 'echo Darwin'
stub "$mac" sysctl 'case "$2" in
  hw.ncpu) echo 3 ;;
  hw.memsize) echo 7516192768 ;;
  hw.pagesize) echo 16384 ;;
  kern.hv_vmm_present) echo 1 ;;
  machdep.cpu.brand_string) echo "Apple M1 (Virtual)" ;;
esac'
stub "$mac" vm_stat 'printf "Pages free:                               65536.\nPages inactive:                           32768.\nPages speculative:                        0.\nPages active:  1.\n"'
stub "$mac" uptime 'echo "10:00  up 1 day, load averages: 2.50 2.10 1.90"'
out="$(PATH="$mac:$basics" bash "$script" start)"
contains "macOS: the label heads the facts" "$out" "runner facts (start)"
contains "macOS: cores from hw.ncpu" "$out" "cores: 3"
contains "macOS: memory total and available" "$out" "memory: total 7168 MiB, available 1536 MiB"
contains "macOS: load from uptime" "$out" "load averages: 2.50"
contains "macOS: a VM says so" "$out" "vm: hv_vmm_present=1 cpu=Apple M1 (Virtual)"

# A Linux runner: nproc, free and systemd-detect-virt, which exits 1 on bare metal but still names it.
linux="$tmp/linux"
mkdir -p "$linux"
stub "$linux" uname 'echo Linux'
stub "$linux" nproc 'echo 4'
stub "$linux" free 'printf "              total        used        free      shared  buff/cache   available\nMem:          15990        2000       10000          10        3990       13500\n"'
stub "$linux" uptime 'echo " 10:00:00 up 1:00,  0 users,  load average: 4.10, 3.00, 2.00"'
stub "$linux" systemd-detect-virt 'echo microsoft; exit 0'
out="$(PATH="$linux:$basics" bash "$script" end)"
contains "Linux: cores from nproc" "$out" "cores: 4"
contains "Linux: memory from free" "$out" "memory: total 15990 MiB, available 13500 MiB"
contains "Linux: load from uptime" "$out" "load average: 4.10"
contains "Linux: VM type from systemd-detect-virt" "$out" "vm: microsoft"
stub "$linux" systemd-detect-virt 'echo none; exit 1'
contains "Linux: bare metal is named, not unknown" "$(PATH="$linux:$basics" bash "$script" end)" "vm: none"

# A host with none of the tools (a Windows runner's Git Bash has no sysctl, free or systemd-detect-virt).
bare="$tmp/bare"
mkdir -p "$bare"
stub "$bare" uname 'echo MINGW64_NT-10.0'
out="$(PATH="$bare:$basics" bash "$script" start)"
rc=$?
expect "a host without the tools still succeeds" "$rc" 0
contains "a missing core count is unknown" "$out" "cores: unknown"
contains "a missing load is unknown" "$out" "load: unknown"

# The facts are appended to the file, start and end both.
PATH="$mac:$basics" bash "$script" start "$tmp/logs/runner-facts.txt" >/dev/null
PATH="$mac:$basics" bash "$script" end "$tmp/logs/runner-facts.txt" >/dev/null
expect "start and end both land in the file" "$(grep -c '^runner facts' "$tmp/logs/runner-facts.txt")" 2
expect "an unwritable file does not fail the step" "$(PATH="$mac:$basics" bash "$script" start /dev/null/x >/dev/null; echo $?)" 0

# The busiest processes come from ps, busiest first.
stub "$mac" ps 'printf "  101     1   3.0  1000  0:01.00 /usr/bin/idle\n  202     1  95.5  9000  9:59.00 /opt/java/bin/java\n"'
out="$(PATH="$mac:$basics" bash "$script" start)"
contains "the busiest processes are listed" "$out" "busiest processes"
expect "the busiest process comes first" "$(grep -A1 'busiest processes' <<<"$out" | tail -1 | awk '{ print $1 }')" 202

# start with a file samples mid-step until end.
: >"$tmp/mid.txt"
RUNNER_TEMP="$tmp" RUNNER_FACTS_PERIOD=1 PATH="$mac:$basics" bash "$script" start "$tmp/mid.txt" >/dev/null
sleep 3
RUNNER_TEMP="$tmp" PATH="$mac:$basics" bash "$script" end "$tmp/mid.txt" >/dev/null
mids="$(grep -c '^runner facts (mid)' "$tmp/mid.txt")"
expect "start samples mid-step" "$([ "$mids" -ge 1 ] && echo yes)" yes
sleep 2
expect "end stops the sampler" "$(grep -c '^runner facts (mid)' "$tmp/mid.txt")" "$mids"
expect "end clears the pid file" "$([ -e "$tmp/runner-facts-sampler.pid" ] && echo left || echo cleared)" cleared

echo "---"
echo "runner-facts: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
