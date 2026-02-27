#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <dump_step1h.tsv> <dump_step6h.tsv>"
  exit 1
fi

f1="$1"
f2="$2"

if [[ ! -f "$f1" || ! -f "$f2" ]]; then
  echo "Both files must exist."
  exit 1
fi

tmp1="$(mktemp)"
tmp2="$(mktemp)"
trap 'rm -f "$tmp1" "$tmp2"' EXIT

summarize() {
  local in="$1"
  local out="$2"
  awk -F'\t' '
    NR==44{
      for(i=1;i<=NF;i++){
        if($i=="lat") lat=i
        else if($i=="precip_kgm2day") p=i
        else if($i=="evap_kgm2day") e=i
        else if($i=="soilMoist") s=i
        else if($i=="atmMoist") a=i
      }
      next
    }
    NR>44{
      n++
      sp+=$p; se+=$e; ss+=$s; sa+=$a
      la=($lat<0)?-$lat:$lat
      b=(la<15)?"00-15":(la<30)?"15-30":(la<45)?"30-45":(la<60)?"45-60":"60-90"
      bn[b]++; bp[b]+=$p; be[b]+=$e; bs[b]+=$s; ba[b]+=$a
    }
    END{
      printf "GLOBAL\tN=%d\tP=%.4f\tE=%.4f\tSoil=%.4f\tAtm=%.4f\n", n, sp/n, se/n, ss/n, sa/n
      for (k in bn) {
        printf "BAND\t%s\tN=%d\tP=%.4f\tE=%.4f\tSoil=%.4f\tAtm=%.4f\n", k, bn[k], bp[k]/bn[k], be[k]/bn[k], bs[k]/bn[k], ba[k]/bn[k]
      }
    }
  ' "$in" | sort > "$out"
}

summarize "$f1" "$tmp1"
summarize "$f2" "$tmp2"

echo "== Step 1 =="
cat "$tmp1"
echo "== Step 2 =="
cat "$tmp2"
echo "== Relative Delta (%): step2 vs step1 =="

awk -F'\t' '
  FNR==NR{
    key=$1 FS $2
    for(i=3;i<=NF;i++) {
      split($i,kv,"="); v1[key,kv[1]]=kv[2]
    }
    keys[key]=1
    next
  }
  {
    key=$1 FS $2
    if(!(key in keys)) next
    printf "%s", key
    metrics[1]="P"; metrics[2]="E"; metrics[3]="Soil"; metrics[4]="Atm"
    for(mi=1; mi<=4; mi++){
      m=metrics[mi]
      a=v1[key,m]+0.0
      b=0.0
      for(i=3;i<=NF;i++){split($i,kv,"="); if(kv[1]==m) b=kv[2]+0.0}
      if (a==0.0) d=0.0; else d=(b-a)/a*100.0
      printf "\t%s=%.2f%%", m, d
    }
    printf "\n"
  }
' "$tmp1" "$tmp2"

