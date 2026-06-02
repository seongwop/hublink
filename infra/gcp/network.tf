# VPC, 서브넷, 내부 통신, SSH, 공개 서비스 방화벽 설정

# HubLink 실습 리소스 전용 VPC
resource "google_compute_network" "main" {
  name                    = "${var.name_prefix}-vpc"
  auto_create_subnetworks = false

  depends_on = [google_project_service.required]
}

# VM 4대 배치용 단일 서브넷
resource "google_compute_subnetwork" "main" {
  name                     = "${var.name_prefix}-subnet"
  ip_cidr_range            = var.network_cidr
  region                   = var.region
  network                  = google_compute_network.main.id
  private_ip_google_access = true
}

# HubLink VM 간 TCP, UDP, ICMP 허용
resource "google_compute_firewall" "internal" {
  name    = "${var.name_prefix}-allow-internal"
  network = google_compute_network.main.name

  source_ranges = [var.network_cidr]
  target_tags   = ["hublink"]

  allow {
    protocol = "tcp"
  }

  allow {
    protocol = "udp"
  }

  allow {
    protocol = "icmp"
  }
}

# 외부 IP 직접 SSH 접근 허용
resource "google_compute_firewall" "ssh" {
  name    = "${var.name_prefix}-allow-ssh"
  network = google_compute_network.main.name

  source_ranges = var.ssh_source_ranges
  target_tags   = ["hublink-ssh"]

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

# GitHub Actions IAP 터널 SSH 허용
resource "google_compute_firewall" "ssh_iap" {
  name    = "${var.name_prefix}-allow-iap-ssh"
  network = google_compute_network.main.name

  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["hublink-ssh"]

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

# platform VM 공개 확인 포트
resource "google_compute_firewall" "platform_public" {
  name    = "${var.name_prefix}-allow-platform-public"
  network = google_compute_network.main.name

  source_ranges = var.public_source_ranges
  target_tags   = ["hublink-platform"]

  allow {
    protocol = "tcp"
    ports    = ["19090", "19091"]
  }
}

# 모니터링 VM 공개 확인 포트
resource "google_compute_firewall" "monitor_public" {
  name    = "${var.name_prefix}-allow-monitor-public"
  network = google_compute_network.main.name

  source_ranges = var.public_source_ranges
  target_tags   = ["hublink-monitor"]

  allow {
    protocol = "tcp"
    ports    = ["3000", "8082", "9090", "9411"]
  }
}
