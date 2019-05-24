#author Khayam Gondal kanjam@g.clemson.edu
#This script can install packages on multiple nodes at the same time to save time

import sys, os, string, threading
import paramiko

def host_conn(host):
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username=user, key_filename=key)
    update_nodes(host, ssh)
    # install_ovs(host, ssh)
    # set_eth_ip(host, ssh)

def agent_conn(host):
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username=user, key_filename=key)
    update_nodes(host, ssh)
    install_java_8(host, ssh)

    setup_sos(host, ssh)
    #install_infini_drivers(host, ssh)
    #set_infini_ip(host, ssh)
    install_ovs(host, ssh)
    set_eth_ip(host, ssh)
    # set_infini_route(host, ssh)

def wan_conn(host):
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username=user, key_filename=key)
    update_nodes(host, ssh)
    install_infini_drivers(host, ssh)
    # set_infini_ip(host, ssh)
    install_java_8(host, ssh)
    #setup_controller(host, ssh)

def update_nodes(host, ssh):
    stdin, stdout, stderr = ssh.exec_command('sudo apt-get update')
    exit_status = stdout.channel.recv_exit_status()
    if exit_status == 0:
        pass
    else:
        print(host + " Failed to APT update")

def install_infini_drivers(host, ssh):
    stdin, stdout, stderr = ssh.exec_command('wget http://content.mellanox.com/ofed/MLNX_OFED-4.3-1.0.1.0/MLNX_OFED_LINUX-4.3-1.0.1.0-ubuntu16.04-x86_64.iso')
    stdout.channel.recv_exit_status()

    stdin, stdout, stderr = ssh.exec_command('sudo mkdir /mnt/mx && sudo mount -o loop MLNX_OFED_LINUX-4.3-1.0.1.0-ubuntu16.04-x86_64.iso /mnt/mx && sudo /mnt/mx/mlnxofedinstall')
    stdin.write('y')
    exit_status = stdout.channel.recv_exit_status()

    stdin, stdout, stderr = ssh.exec_command('sudo /etc/init.d/openibd restart')
    stdout.channel.recv_exit_status()

    if exit_status == 0:
        pass
    else:
        print(host + " Failed to install drivers")

# def set_infini_ip(host, ssh):
#     stdin, stdout, stderr = ssh.exec_command('hostname')
#     exit_status = stdout.channel.recv_exit_status()
#     out_lines = stdout.readline()
#     hostname = out_lines.split('.')[0]
#     stdin, stdout, stderr = ssh.exec_command('sudo ifconfig ib0 ' + infini_ip_map.get(hostname) + '/24 up')
#     exit_status = stdout.channel.recv_exit_status()
#     if exit_status != 0:
#         print(host + " Failed to get hostname or set IP ")

def set_eth_ip(host, ssh):
    stdin, stdout, stderr = ssh.exec_command('hostname')
    exit_status = stdout.channel.recv_exit_status()
    out_lines = stdout.readline()
    hostname = out_lines.split('.')[0]
    stdin, stdout, stderr = ssh.exec_command('sudo ifconfig br0 ' + eth_ip_map.get(hostname) + '/24 up')
    exit_status = stdout.channel.recv_exit_status()
    if exit_status != 0:
        print(host + " Failed to get hostname or set Eth IP on " + hostname)


def set_infini_route(host, ssh):
    stdin, stdout, stderr = ssh.exec_command('sudo route add -net 172.0.0.0/24 gw 172.0.0.100')
    exit_status = stdout.channel.recv_exit_status()
    if exit_status != 0:
        print(host + " Failed to set route")

def install_java_8(host, ssh):
    stdin, stdout, stderr = ssh.exec_command('sudo apt install -y openjdk-8-jdk')
    exit_status = stdout.channel.recv_exit_status()
    if exit_status == 0:
        print(host + " Java installed")
        pass
    else:
        print(host + " Failed to install Java")

def install_ovs(host, ssh):
    stdin, stdout, stderr = ssh.exec_command('sudo apt install -y openvswitch-switch')
    exit_status = stdout.channel.recv_exit_status()
    if exit_status == 0:
        print(host + " OVS installed")
        setup_ovs(host, ssh)
    else:
        print(host + " Failed to install OVS")

def setup_ovs(host, ssh):
    stdin, stdout, stderr = ssh.exec_command('sudo ovs-vsctl add-br br0 && sudo ovs-vsctl add-port br0 '+ interface_name +' && sudo ifconfig '+ interface_name + ' 0 up ')
    exit_status = stdout.channel.recv_exit_status()
    stdin, stdout, stderr = ssh.exec_command('sudo ovs-vsctl set bridge br0 protocols=OpenFlow13')
    exit_status = stdout.channel.recv_exit_status()
    stdin, stdout, stderr = ssh.exec_command('sudo ovs-vsctl set-controller br0 tcp:'+ ctl_ip + ':'+ctl_port )
    exit_status = stdout.channel.recv_exit_status()
    if exit_status == 0:
        pass
    else:
        print(host + " Failed to setup OVS")

def setup_controller(host, ssh):
    install_maven(host, ssh)
    stdin, stdout, stderr = ssh.exec_command('git clone http://github.com/khayamgondal/floodlight && cd floodlight/ && git checkout shellaN && mvn package -Dmaven.test.skip=true')
    exit_status = stdout.channel.recv_exit_status()
    if exit_status == 0:
        pass
    else:
        print(host + " Failed to setup floodlight controller")

def setup_sos(host, ssh):
    install_maven(host, ssh)
    stdin, stdout, stderr = ssh.exec_command('git clone https://github.com/QingWang0909/SOSAgent.git && cd SOSAgent/ && git checkout dev && mvn package -Dmaven.test.skip=true')
    exit_status = stdout.channel.recv_exit_status()
    if exit_status == 0:
        pass
    else:
        print(host + " Failed to setup sos")

def install_maven(host, ssh):
    stdin, stdout, stderr = ssh.exec_command('sudo apt install -y maven')
    exit_status = stdout.channel.recv_exit_status()
    if exit_status == 0:
        setup_ovs(host, ssh)
    else:
        print(host + " Failed to install maven")



clients = ['apt175.apt.emulab.net']

agents = ['apt177.apt.emulab.net']

infini_ip_map = {'agent1':'172.0.0.11', 'agent2':'172.0.0.12', 'agent3':'172.0.0.13', 'agent4':'172.0.0.14', 'agent5':'172.0.0.15', 'agent6':'172.0.0.16',
                 'agent7':'172.0.0.17', 'agent8':'172.0.0.18', 'agent9':'172.0.0.19', 'agent10':'172.0.0.10','wan':'172.0.0.100' }

eth_ip_map = {'serveragent':'10.0.0.3', 'clientagent':'10.0.0.2', 'client':'10.0.0.1', 'server':'10.0.0.4'}

servers = ['apt159.apt.emulab.net']

wan = 'apt171.apt.emulab.net'

user = 'QW'
key = '/Users/qingwang/.ssh/cloudlab.pem'

interface_name = 'enp8s0d1'
interface_name2 = 'enp3s0f0'
ctl_ip = "128.110.96.124"
ctl_port="6663"

threads = []

# for node in clients + servers:
#     t = threading.Thread(target=host_conn(node), args=(node))
#     t.start()
#     threads.append(t)
# for t in threads:
#     t.join()

for node in agents:
    t = threading.Thread(target=agent_conn(node), args=(node))
    t.start()
    threads.append(t)
for t in threads:
    t.join()

#wan_conn(wan)