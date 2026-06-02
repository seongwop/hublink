
# VPC, 서브넷, 내부 통신/SSH/Gateway/모니터링용 방화벽 규칙 생성

resource "google_compute_network" "main" {
  name                    = "${var.name_prefix}-vpc"
  auto_create_subnetworks = false

  depends_on = [google_project_service.required]
}

resource "google_compute_subnetwork" "main" {
  name                     = "${var.name_prefix}-subnet"
  ip_cidr_range            = var.network_cidr
  region                   = var.region
  network                  = google_compute_network.main.id
  private_ip_google_access = true
}

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
