#!/bin/sh


for i in `seq 1 1`
do
    > port500$i.log
    iperf -c 10.0.0.4 -t 100 -i 1 -p 500$i > port500$i.log &
done

exit 0