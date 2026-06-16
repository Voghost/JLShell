#!/bin/sh
# JLShell system info polling script
# Output format: OS|||CPU|||MEM|||DISK|||IP

# OS & arch
printf '%s' "$(uname -s) $(uname -m)"
printf '|||'

# CPU: instant snapshot from /proc/stat (Linux), fallback to nproc
if [ -r /proc/stat ]; then
    awk '/^cpu /{total=$2+$3+$4+$5+$6+$7+$8; idle=$5; if(total>0) printf "%d", 100*(total-idle)/total; else printf "?"}' /proc/stat
else
    nproc 2>/dev/null || printf '?'
fi
printf '|||'

# MEM: free (Linux), /proc/meminfo fallback, vm_stat (macOS)
if command -v free >/dev/null 2>&1; then
    free | awk '/^Mem:/{printf "%d", $3*100/$2}'
elif [ -r /proc/meminfo ]; then
    awk '/MemTotal/{t=$2}/MemAvailable/{a=$2} END{if(t>0) printf "%d", (t-a)*100/t}' /proc/meminfo
elif command -v vm_stat >/dev/null 2>&1; then
    vm_stat | awk '/Pages free/{f=$3}/Pages active/{a=$3}/Pages wired/{w=$3}/Pages inactive/{i=$3} END{if(f+a+w+i>0) printf "%d", (a+w)/(f+a+w+i)*100}'
else
    printf '?'
fi
printf '|||'

# Disk: root partition usage %
df -h / 2>/dev/null | awk 'NR==2{gsub(/%/,"",$5); print $5}' || printf '?'
printf '|||'

# IP: hostname -I (Linux), ifconfig fallback
hostname -I 2>/dev/null | awk '{print $1}' || \
    ifconfig 2>/dev/null | grep 'inet ' | grep -v 127.0.0.1 | head -1 | awk '{print $2}' || \
    printf '?'
