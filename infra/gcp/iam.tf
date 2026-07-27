# VM과 GitHub Actions 배포에 필요한 서비스 계정 및 IAM 권한

# VM 이미지 pull과 로그/메트릭용 서비스 계정
resource "google_service_account" "vm" {
  account_id   = "${var.name_prefix}-vm"
  display_name = "HubLink VM service account"

  depends_on = [google_project_service.required]
}

# VM Artifact Registry 이미지 pull 권한
resource "google_project_iam_member" "artifact_registry_reader" {
  project = var.project_id
  role    = "roles/artifactregistry.reader"
  member  = "serviceAccount:${google_service_account.vm.email}"
}

# VM Cloud Logging 기록 권한
resource "google_project_iam_member" "logging_writer" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.vm.email}"
}

# VM Cloud Monitoring 메트릭 기록 권한
resource "google_project_iam_member" "monitoring_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.vm.email}"
}

# GitHub Actions 이미지 push와 VM 배포용 서비스 계정
resource "google_service_account" "github_actions" {
  account_id   = "${var.name_prefix}-github-actions"
  display_name = "HubLink GitHub Actions deploy service account"

  depends_on = [google_project_service.required]
}

# GitHub Actions Artifact Registry 이미지 push 권한
resource "google_project_iam_member" "github_actions_artifact_registry_writer" {
  project = var.project_id
  role    = "roles/artifactregistry.writer"
  member  = "serviceAccount:${google_service_account.github_actions.email}"
}

# GitHub Actions VM 조회, SSH, SCP 권한
resource "google_project_iam_member" "github_actions_compute_admin" {
  project = var.project_id
  role    = "roles/compute.admin"
  member  = "serviceAccount:${google_service_account.github_actions.email}"
}

# GitHub Actions IAP 터널 접근 권한
resource "google_project_iam_member" "github_actions_iap_tunnel_user" {
  project = var.project_id
  role    = "roles/iap.tunnelResourceAccessor"
  member  = "serviceAccount:${google_service_account.github_actions.email}"
}

# GitHub Actions Compute 작업 보조 권한
resource "google_project_iam_member" "github_actions_service_account_user" {
  project = var.project_id
  role    = "roles/iam.serviceAccountUser"
  member  = "serviceAccount:${google_service_account.github_actions.email}"
}

# Cloud Run 부하 테스트 실행 계정
resource "google_service_account" "load_test" {
  account_id   = "${var.name_prefix}-load-test"
  display_name = "HubLink Cloud Run load test service account"

  depends_on = [google_project_service.required]
}

# 부하 테스트 DB 비밀번호 저장소
resource "google_secret_manager_secret" "load_test_db_password" {
  secret_id = "${var.name_prefix}-load-test-db-password"

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}

# Cloud Run Job의 DB 비밀번호 조회 권한
resource "google_secret_manager_secret_iam_member" "load_test_secret_accessor" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.load_test_db_password.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.load_test.email}"
}

# GitHub Actions의 Cloud Run Job 배포 권한
resource "google_project_iam_member" "github_actions_run_admin" {
  project = var.project_id
  role    = "roles/run.admin"
  member  = "serviceAccount:${google_service_account.github_actions.email}"
}

# GitHub Actions의 부하 테스트 비밀번호 버전 추가 권한
resource "google_secret_manager_secret_iam_member" "github_actions_secret_version_adder" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.load_test_db_password.secret_id
  role      = "roles/secretmanager.secretVersionAdder"
  member    = "serviceAccount:${google_service_account.github_actions.email}"
}
