# GitHub Actions 키 없는 GCP 인증 연동

# Workload Identity 리소스 경로용 프로젝트 번호 조회
data "google_project" "current" {
  project_id = var.project_id
}

# GitHub Actions OIDC 토큰을 받을 Workload Identity Pool
resource "google_iam_workload_identity_pool" "github_actions" {
  workload_identity_pool_id = "${var.name_prefix}-github"
  display_name              = "HubLink GitHub Actions"
  description               = "HubLink GitHub Actions OIDC 인증 풀"

  depends_on = [google_project_service.required]
}

# GitHub OIDC issuer와 repository 조건 매핑
resource "google_iam_workload_identity_pool_provider" "github_actions" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github_actions.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-actions"
  display_name                       = "GitHub Actions"
  description                        = "GitHub Actions OIDC provider"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.actor"      = "assertion.actor"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  attribute_condition = "assertion.repository == '${var.github_repository}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

# 지정 repository의 GitHub Actions만 서비스 계정 impersonation 허용
resource "google_service_account_iam_member" "github_actions_workload_identity_user" {
  service_account_id = google_service_account.github_actions.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/projects/${data.google_project.current.number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github_actions.workload_identity_pool_id}/attribute.repository/${var.github_repository}"
}
