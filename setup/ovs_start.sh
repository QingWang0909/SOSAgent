#!/bin/sh

# Variables
bridge=br0
interface=enp8s0d1
CONTROLLER_IP="128.110.96.124"
OPENFLOW_PORT="6663"

# Start OVS
sudo ovsdb-server /usr/local/etc/openvswitch/conf.db \
--remote=punix:/usr/local/var/run/openvswitch/db.sock \
--remote=db:Open_vSwitch,Open_vSwitch,manager_options \
--private-key=db:Open_vSwitch,SSL,private_key \
--certificate=db:Open_vSwitch,SSL,certificate \
--bootstrap-ca-cert=db:Open_vSwitch,SSL,ca_cert --pidfile --detach --log-file

sudo ovs-vsctl --no-wait init
sudo ovs-vswitchd --pidfile --detach
sudo ps aux |grep ovs
sudo ovs-vsctl show
sudo ovs-vsctl --version

# Create & configure OVS bridge
sudo ovs-vsctl add-br $bridge
sudo ovs-vsctl add-port br0 $interface
sudo ifconfig $interface 0 up
sudo ovs-vsctl set bridge $bridge protocols=OpenFlow10

# Connect OVS to controller
sudo ovs-vsctl set-controller $bridge tcp:$CONTROLLER_IP:$OPENFLOW_PORT