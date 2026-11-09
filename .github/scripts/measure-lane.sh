#!/usr/bin/env bash
# TEMPORARY measurement scaffolding (removed before merge). Usage:
#   measure-lane.sh sample <outfile>   # loops forever, one line every 2 s
#   measure-lane.sh extract <outdir>   # pulls markers + testcase times out of the test XML
set -uo pipefail
case "$1" in
  sample)
    while true; do
      ts=$(date +%H:%M:%S)
      load=$(sysctl -n vm.loadavg | tr -d '{}')
      swap=$(sysctl -n vm.swapusage | sed 's/  */ /g')
      free=$(memory_pressure -Q 2>/dev/null | awk -F': ' '/percentage/ {print $2}')
      vm=$(vm_stat | awk '/Pageouts|Swapouts|Pageins/ {gsub("\\.","",$NF); printf "%s=%s ", $1, $NF}')
      top=$(ps -Ao %cpu=,rss=,comm= -r | head -4 | awk '{n=split($3,a,"/"); printf "%s:%s%%/%dM ", a[n], $1, $2/1024}')
      echo "$ts load=[$load] free=$free swap=[$swap] $vm top=[$top]"
      sleep 2
    done >> "$2"
    ;;
  probe)
    # Spawns the socket-udp test binary into the booted simulator every ~2 s, once it exists, and
    # records NW's first report for a cellular-required connect from a fresh process each time.
    out="$2"
    kexe=socket-udp/build/bin/iosSimulatorArm64/debugTest/test.kexe
    until [ -f "$kexe" ] && [ $(( $(date +%s) - $(stat -f %m "$kexe") )) -gt 10 ]; do sleep 2; done
    echo "$(date +%H:%M:%S) PROBE-START" >> "$out"
    while true; do
      line=$(xcrun simctl spawn "$IOS_SIMULATOR_BOOTED_UDID" "$PWD/$kexe" \
        --ktest_filter='*AppleRequiredInterfaceConnectTests.aConnectRequiringAnAbsent*' 2>&1 | grep NW-FIRST-REPORT)
      echo "$(date +%H:%M:%S) $(cat "$RUNNER_TEMP/phase" 2>/dev/null) ${line:-NO-REPORT}" >> "$out"
      sleep 2
    done
    ;;
  extract)
    out="$2"; mkdir -p "$out"
    python3 - "$out" <<'PY'
import glob, sys, os, xml.etree.ElementTree as ET
out = sys.argv[1]
rows, markers = [], []
for f in glob.glob('**/build/test-results/iosSimulatorArm64Test/*.xml', recursive=True):
    mod = f.split('/build/')[0] or 'root'
    try:
        root = ET.parse(f).getroot()
    except Exception as e:
        print('unparsable', f, e); continue
    suites = [root] if root.tag == 'testsuite' else root.findall('testsuite')
    for s in suites:
        ts = s.get('timestamp', '')
        for c in s.findall('testcase'):
            rows.append(f"{mod}\t{ts}\t{c.get('classname')}\t{c.get('name')}\t{c.get('time')}\t{'fail' if c.find('failure') is not None else 'ok'}")
        for so in s.iter('system-out'):
            for line in (so.text or '').splitlines():
                if line.startswith(('NW-FIRST-REPORT', 'NW-SLOW', 'SLOW-')):
                    markers.append(f"{mod}\t{ts}\t{line}")
open(os.path.join(out, 'testcases.tsv'), 'w').write('\n'.join(rows) + '\n')
open(os.path.join(out, 'markers.txt'), 'w').write('\n'.join(markers) + '\n')
print(f"{len(rows)} testcases, {len(markers)} markers")
for m in markers: print(m)
PY
    ;;
esac
