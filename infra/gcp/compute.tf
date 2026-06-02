
# Compute Engine VM, 고정 내부 IP, 부팅 디스크, 시작 스크립트 생성

data "google_compute_image" "boot" {
  family  = var.boot_image_family
  project = var.boot_image_project
}

resource "google_compute_address" "internal" {
  for_each = var.vm_specs

  name         = "${var.name_prefix}-${each.key}-internal-ip"
  address_type = "INTERNAL"
  address      = each.value.internal_ip
  region       = var.region
  subnetwork   = google_compute_subnetwork.main.id
}

resource "google_compute_instance" "vm" {
  for_each = var.vm_specs

  name         = "${var.name_prefix}-${each.key}-vm"
  machine_type = each.value.machine_type
  zone         = var.zone
  tags         = each.value.tags
  labels = {
    app  = var.name_prefix
    role = each.value.role
  }

  boot_disk {
    initialize_params {
      image = data.google_compute_image.boot.self_link
      size  = each.value.disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.main.id
    network_ip = google_compute_address.internal[each.key].address

    # VM이 인터넷으로 패키지를 설치하고 Docker 이미지를 받을 수 있도록 외부 IP를 붙입니다.
    # 접속 가능한 포트와 접속 허용 IP는 network.tf의 방화벽 규칙에서 제한합니다.
    access_config {}
  }

  metadata = var.ssh_public_key == "" ? {} : {
    ssh-keys = "${var.ssh_username}:${var.ssh_public_key}"
  }

  metadata_startup_script = templatefile("${path.module}/scripts/install-docker.sh", {
    linux_user = var.ssh_username
    region     = var.region
    role       = each.value.role
  })

  service_account {
    email = google_service_account.vm.email
    scopes = [
      "https://www.googleapis.com/auth/cloud-platform",
    ]
  }

  allow_stopping_for_update = true

  depends_on = [
    google_project_iam_member.artifact_registry_reader,
    google_project_iam_member.logging_writer,
    google_project_iam_member.monitoring_writer,
  ]
}
