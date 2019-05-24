#!/bin/sh

sudo apt-get update && sudo apt-get install -y apt-transport-https curl
sudo curl -s https://packages.cloud.google.com/apt/doc/apt-key.gpg | sudo apt-key add -
sudo tee /etc/apt/sources.list.d/kubernetes.list <<-'EOF'
deb https://mirrors.aliyun.com/kubernetes/apt kubernetes-xenial main
EOF
sudo apt-get update

sudo apt install kubernetes-cni=0.6.0-00
sudo apt-get install -y kubelet=1.12.0-00 kubeadm=1.12.0-00
sudo apt-mark hold kubelet=1.12.0-00 kubeadm=1.12.0-00
sudo systemctl enable kubelet && sudo systemctl start kubelet
apt-cache madison kubeadm


# Download k8s V1.12.0 images
sudo docker pull anjia0532/google-containers.kube-proxy:v1.12.0
sudo docker pull anjia0532/google-containers.pause:3.1


# Tag k8s V1.12.0 images
sudo docker tag anjia0532/google-containers.kube-proxy:v1.12.0 k8s.gcr.io/kube-proxy:v1.12.0
sudo docker tag anjia0532/google-containers.pause:3.1 k8s.gcr.io/pause:3.1


