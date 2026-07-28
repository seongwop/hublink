# Terraform 관리 대상 GCP API 목록

# VM, 이미지 저장소, IAM, IAP, OIDC 인증 API 목록
locals {
  required_services = toset([
    "compute.googleapis.com",
    "artifactregistry.googleapis.com",
    "iam.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "serviceusage.googleapis.com",
    "iap.googleapis.com",
    "sts.googleapis.com",
    "iamcredentials.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
  ])
}

# 프로젝트 필수 Google Cloud API 활성화
resource "google_project_service" "required" {
  for_each = local.required_services

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}
