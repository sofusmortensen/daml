# Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

resource "google_compute_instance" "hoogle" {
  name-prefix  = "hoogle_"
  machine_type = "n1-standard-1"
  tags         = ["daml-hoogle"]
  labels       = "${local.labels}"
  region       = "${local.region}"

  boot_disk = {
    initiliaze_params {
      size  = 20
      image = "ubuntu-os-cloud/ubuntu-1604-lts"
    }
  }

  metadata_startup_script = <<STARTUP
#! /bin/bash
apt-get update
apt-get -y upgrade
### stackdriver
curl -sSL https://dl.google.com/cloudagents/install-logging-agent.sh | bash
### nginx
apt-get -y install nginx
cat > /etc/nginx/nginx.conf <<NGINX
user www-data;
worker_processes auto;
pid /run/nginx.pid;
events {
  worker_connections 768;
}
http {
  sendfile on;
  tcp_nopush on;
  tcp_nodelay on;
  keepalive_timeout 65;
  types_hash_max_size 2048;
  include /etc/nginx/mime.types;
  default_type application/octet-stream;
  access_log /var/log/nginx/access.log;
  error_log /var/log/nginx/error.log;
  server {
    listen 8081 default_server;
    server_name _;
    return 307 https://hoogle.daml.com$request_uri;
  }
}
NGINX
service nginx restart
### hoogle
apt-get -y install curl git
useradd hoogle
mkdir /home/hoogle
chown hoogle:hoogle /home/hoogle
cd /home/hoogle
curl -sSL https://get.haskellstack.org/ | sh
runuser -u hoogle bash <<HOOGLE_SETUP
git clone https://github.com/ndmitchell/hoogle.git
cd hoogle
stack init --resolver=nightly
stack build
stack install
export PATH=/home/hoogle/.local/bin:$PATH
mkdir daml
curl https://docs.daml.com/hoogle_db/base.txt --output daml/base.txt
hoogle generate --database=daml.hoo --local=daml
nohup hoogle server --database=daml.hoo --log=.log.txt --port=8080 >> out.txt &
HOOGLE_SETUP
cat > /home/hoogle/refresh-db.sh <<CRON

CRON
STARTUP

  lifecycle {
    create_before_destroy = true
  }

  network_interface {
    network       = "default"
    access_config = {}
  }

  service_account {
    email  = "log-writer@da-dev-gcp-daml-language.iam.gserviceaccount.com"
    scopes = ["cloud-platform"]
  }
}

resource "google_compute_global_address" "hoogle" {
  name       = "hoogle"
  ip_version = "IPV4"
}

resource "google_compute_http_health_check" "hoogle" {
  name               = "hoogle"
  request_path       = "/"
  check_interval_sec = 30
  timeout_sec        = 30
}

resource "google_compute_instance_group" "hoogle" {
  name      = "hoogle"
  zone      = "${local.zone}"
  instances = ["${google_compute_instance.hoogle.self_link}"]
}

resource "google_compute_backend_service" "hoogle" {
  name          = "hoogle"
  health_checks = ["${google_compute_http_health_check.hoogle.self_link}"]

  backend {
    group = "${google_compute_instance_group.hoogle.self_link}"
  }
}

resource "google_compute_url_map" "hoogle" {
  name            = "hoogle"
  default_service = "${google_compute_backend_service.hoogle.self_link}"
}

resource "google_compute_target_http_proxy" "hoogle" {
  name    = "hoogle"
  url_map = "${google_compute_url_map.hoogle.self_link}"
}

resource "google_compute_global_forwarding_rule" "hoogle_http" {
  name       = "hoogle_http"
  target     = "${google_compute_target_http_proxy.hoogle.self_link}"
  ip_address = "${google_compute_global_address.hoogle.address}"
  port_range = "80"
}

/*
resource "google_compute_target_https_proxy" "hoogle" {
  name             = "hoogle"
  url_map          = "${google_compute_url_map.hoogle.self_link}"
  ssl_certificates = ["${local.ssl_certificate_daml}"]
}

resource "google_compute_global_forwarding_rule" "hoogle_https" {
  name       = "hoogle_https"
  target     = "${google_compute_target_https_proxy.hoogle.self_link}"
  ip_address = "${google_compute_global_address.hoogle.address}"
  port_range = "443"
  depends_on = ["google_compute_global_address.hoogle"]
}
*/

output "hoogle_address" {
  value = "${google_compute_global_address.hoogle.address}"
}
