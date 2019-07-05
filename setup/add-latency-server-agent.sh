#!/bin/bash

# This script runs at server agent node and will add 25ms latency on interface enp130s0f0

interface=enp130s0f0
ip1=10.0.0.1
ip2=10.0.0.2
delay=25ms
buffer_size=5GB

tc qdisc del dev $interface root
tc qdisc add dev $interface root handle 1: prio
tc filter add dev $interface parent 1:0 protocol ip prio 1 u32 match ip dst $ip1 flowid 2:1
tc filter add dev $interface parent 1:0 protocol ip prio 1 u32 match ip dst $ip2 flowid 2:1
tc qdisc add dev $interface parent 1:1 handle 2: netem limit $buffer_size delay $delay