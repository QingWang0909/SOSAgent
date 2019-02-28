#!/bin/sh

# Start OVS
sudo ovsdb-server /usr/local/etc/openvswitch/conf.db \
--remote=punix:/usr/local/var/run/openvswitch/db.sock \
--remote=db:Open_vSwitch,Open_vSwitch,manager_options \
--private-key=db:Open_vSwitch,SSL,private_key \
--certificate=db:Open_vSwitch,SSL,certificate \
--bootstrap-ca-cert=db:Open_vSwitch,SSL,ca_cert --pidfile --detach --log-file

sudo ovs-vsctl --no-wait init
sudo ovs-vswitchd --pidfile --detach
sudo ovs-vsctl show
sudo ovs-vsctl --version

# Create & configure OVS bridge
sudo ovs-vsctl add-br br0
sudo ovs-vsctl add-port br0 enp8s0d1
sudo ifconfig enp8s0d1 0 up
sudo ovs-vsctl set bridge br0 protocols=OpenFlow13

# Connect OVS to controller
CONTROLLER_IP="128.110.96.124"
OPENFLOW_PORT="6663"
sudo ovs-vsctl set-controller br0 tcp:$CONTROLLER_IP:$OPENFLOW_PORT