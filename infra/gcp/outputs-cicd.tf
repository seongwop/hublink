# GitHub Actions 배포 연동 출력값

# GitHub Secret GCP_SERVICE_ACCOUNT_EMAIL 등록값
output "github_actions_service_account_email" {
  description = "GitHub Actions 배포용 서비스 계정 이메일"
  value       = google_service_account.github_actions.email
}

# Cloud Run 부하 테스트 실행 계정
output "load_test_service_account_email" {
  description = "Cloud Run 부하 테스트 실행 계정 이메일"
  value       = google_service_account.load_test.email
}

# GitHub Secret GCP_WORKLOAD_IDENTITY_PROVIDER 등록값
output "github_actions_workload_identity_provider" {
  description = "GitHub Actions Workload Identity Provider"
  value       = google_iam_workload_identity_pool_provider.github_actions.name
}
