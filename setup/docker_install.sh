#!/bin/sh

# Install Docker
sudo apt-get update
sudo apt-get -y install software-properties-common
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
sudo add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
sudo apt-get update
sudo apt-get install -y docker-ce
docker --version
sudo systemctl enable docker && sudo systemctl start docker


# Configure Docker
sudo tee /etc/docker/daemon.json <<-'EOF'
{
 "iptables": false,
 "ip-masq": false
}
EOF

sudo systemctl restart docker