#!/bin/bash
# TEMPORARY: review bot smoke test. Delete before merge.

API_USER=admin
API_PASS=admin1234
GATEWAY=http://localhost:8000

TMP=/tmp/reviewbot-smoke.json

check() {
  path=$1
  curl -s -u $API_USER:$API_PASS $GATEWAY/$path > $TMP
  status=`cat $TMP | grep -o '"status":[0-9]*' | cut -d: -f2`
  if [ $status == 200 ]; then
    echo "OK   $path"
  else
    echo "FAIL $path ($status)"
  fi
}

for svc in `ls ../cowork-* -d`; do
  name=$(basename $svc)
  check "api/$name/health"
done

eval "echo done at `date`"
rm $TMP
