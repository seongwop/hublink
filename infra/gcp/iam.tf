
# VM 서비스 계정을 만들고 이미지 pull, 로그/메트릭 기록 권한을 부여

resource "google_service_account" "vm" {
  account_id   = "${var.name_prefix}-vm"
  display_name = "HubLink VM service account"

  depends_on = [google_project_service.required]
}

resource "google_project_iam_member" "artifact_registry_reader" {
  project = var.project_id
  role    = "roles/artifactregistry.reader"
  member  = "serviceAccount:${google_service_account.vm.email}"
}

resource "google_project_iam_member" "logging_writer" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.vm.email}"
}

resource "google_project_iam_member" "monitoring_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.vm.email}"
}
