#!/bin/sh

# Download OVS
wget http://openvswitch.org/releases/openvswitch-2.6.0.tar.gz
tar -xzvf openvswitch-2.6.0.tar.gz
mv openvswitch-2.6.0 openvswitch
cd openvswitch

# Install OVS
./boot.sh
./configure 
# --with-linux=/lib/modules/`uname -r`/build
sudo make && sudo make install

# Load OVS module into kernel
cd datapath/linux
sudo modprobe openvswitch
lsmod | grep openvswitch

# Create needed files and directories
sudo touch /usr/local/etc/ovs-vswitchd.conf
sudo mkdir -p /usr/local/etc/openvswitch

# Create conf.db in OVS directory
cd ../..
sudo ovsdb-tool create /usr/local/etc/openvswitch/conf.db  vswitchd/vswitch.ovsschema