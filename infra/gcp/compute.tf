# Compute Engine VM, 고정 내부 IP, 부팅 스크립트 설정

# VM 부팅 OS 이미지 선택
data "google_compute_image" "boot" {
  family  = var.boot_image_family
  project = var.boot_image_project
}

locals {
  address_name_suffix = {
    for name, spec in var.vm_specs :
    name => lookup({
      data = "data-monitor"
    }, name, name)
  }
}

# VM별 고정 내부 IP 예약
resource "google_compute_address" "internal" {
  for_each = var.vm_specs

  name         = "${var.name_prefix}-${local.address_name_suffix[each.key]}-internal-ip"
  address_type = "INTERNAL"
  address      = each.value.internal_ip
  region       = var.region
  subnetwork   = google_compute_subnetwork.main.id
}

# VM별 고정 외부 IP 예약
resource "google_compute_address" "external" {
  for_each = {
    for name, spec in var.vm_specs : name => spec
    if contains(var.external_ip_vm_names, name)
  }

  name         = "${var.name_prefix}-${local.address_name_suffix[each.key]}-external-ip"
  address_type = "EXTERNAL"
  region       = var.region

  depends_on = [google_project_service.required]
}

# vm_specs 기준 역할별 VM 생성
resource "google_compute_instance" "vm" {
  for_each = var.vm_specs

  name         = "${var.name_prefix}-${each.key}-vm"
  machine_type = each.value.machine_type
  zone         = var.zone
  tags         = each.value.tags
  resource_policies = var.vm_schedule_enabled && contains(var.scheduled_vm_names, each.key) ? [
    google_compute_resource_policy.vm_schedule.self_link
  ] : []

  # 비용 추적과 리소스 구분용 공통 라벨
  labels = {
    app  = var.name_prefix
    role = each.value.role
  }

  # 비용과 성능 균형형 루트 디스크
  boot_disk {
    initialize_params {
      image = data.google_compute_image.boot.self_link
      size  = each.value.disk_size_gb
      type  = "pd-balanced"
    }
  }

  # 고정 내부 IP와 외부 접속용 IP
  network_interface {
    subnetwork = google_compute_subnetwork.main.id
    network_ip = google_compute_address.internal[each.key].address

    dynamic "access_config" {
      for_each = contains(var.external_ip_vm_names, each.key) ? [1] : []

      content {
        nat_ip = google_compute_address.external[each.key].address
      }
    }
  }

  # SSH 공개키 직접 등록 옵션
  metadata = var.ssh_public_key == "" ? {} : {
    ssh-keys = "${var.ssh_username}:${var.ssh_public_key}"
  }

  # 최초 부팅 시 Docker 설치 스크립트
  metadata_startup_script = replace(templatefile("${path.module}/scripts/install-docker.sh", {
    linux_user        = var.ssh_username
    region            = var.region
    role              = each.value.role
    compose_up_script = file("${path.module}/../../scripts/gcp/hublink-compose-up.sh")
  }), "\r\n", "\n")

  # 이미지 pull과 로그/메트릭 기록용 서비스 계정
  service_account {
    email = google_service_account.vm.email
    scopes = [
      "https://www.googleapis.com/auth/cloud-platform",
    ]
  }

  # 외부 노출 이력이 있는 VM의 부팅 무결성 검증
  shielded_instance_config {
    enable_secure_boot          = contains(var.secure_boot_vm_names, each.key)
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  # 머신 타입 변경 시 stop/start 허용
  allow_stopping_for_update = true

  # 기존 VM 보존
  lifecycle {
    ignore_changes = [
      metadata,
      metadata_startup_script,
      boot_disk[0].initialize_params[0].image,
    ]
  }

  # VM 서비스 계정 권한 부여 후 VM 생성
  depends_on = [
    google_project_iam_member.artifact_registry_reader,
    google_project_iam_member.logging_writer,
    google_project_iam_member.monitoring_writer,
  ]
}
