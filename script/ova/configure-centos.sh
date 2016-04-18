#!/bin/bash
if [ ! -f /etc/redhat-release ]; then
    echo "This script is designed for RedHat-based systems"
    exit 1
fi
if [ "$(id -u)" != "0" ]; then
    echo "Please run as root"
    exit 1
fi
DIST_DIR="$(cd "$(dirname $0)/.." && pwd)"
echo "DIST_DIR=${DIST_DIR}"

# set hostname
hostname ecssync.local

# EPEL repo
yum -y install epel-release

# java
yum -y install java

# NFS/SMB client tools
yum -y install nfs-utils nfs-utils-lib samba-client cifs-utils

# analysis tools
yum -y install iperf telnet

# apache
yum -y install httpd mod_ssl
# configure proxy and auth
cp "${DIST_DIR}/ova/httpd/.htaccess" /etc/httpd
cp "${DIST_DIR}/ova/httpd/conf.d/ecs-sync.conf" /etc/httpd/conf.d
systemctl enable httpd
systemctl start httpd
# allow apache to use the network
setsebool -P httpd_can_network_connect=1

# mysql (mariadb)
yum -y install mariadb-server
# enable UTF-8 support
if [ -f /etc/my.cnf.d/server.cnf ]; then
    sed -i '/\[server\]/a\
innodb_file_format=Barracuda\
innodb_large_prefix=1\
innodb_file_per_table=1' /etc/my.cnf.d/server.cnf
fi
systemctl daemon-reload
systemctl enable mariadb-server
systemctl start mariadb-server
# remove test DBs and set root PW
mysql_secure_installation
# create database for ecs-sync
MYSQL_DIR="$(cd "$(dirname $0)/../mysql" && pwd)"
mysql -u root -p < "${MYSQL_DIR}/utf8/create_mysql_user_db.sql"

# sysctl tweaks
echo '
net.ipv6.conf.all.disable_ipv6 = 1
net.ipv6.conf.default.disable_ipv6 = 1

vm.swappiness = 10' >> /etc/sysctl.conf
sysctl -p

# configure LD_LIBRARY_PATH for CAS SDK
echo '
export LD_LIBRARY_PATH=/usr/local/Centera_SDK/lib/64' >> ~root/.bash_profile

echo 'Done (please manually install iozone, bucket-perf and the CAS SDK)'
