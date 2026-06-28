# 외부 IP 없는 VM의 인터넷 egress
resource "google_compute_router" "nat" {
  count = var.cloud_nat_enabled ? 1 : 0

  name    = "${var.name_prefix}-nat-router"
  network = google_compute_network.main.name
  region  = var.region

  depends_on = [google_project_service.required]
}

# Docker 설치와 이미지 pull용 Cloud NAT
resource "google_compute_router_nat" "main" {
  count = var.cloud_nat_enabled ? 1 : 0

  name                               = "${var.name_prefix}-cloud-nat"
  router                             = google_compute_router.nat[0].name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"

  subnetwork {
    name                    = google_compute_subnetwork.main.id
    source_ip_ranges_to_nat = ["ALL_IP_RANGES"]
  }
}
