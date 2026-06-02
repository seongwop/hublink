# Terraform이 사용할 GCP 프로젝트와 리전 설정

# google_* 리소스 기본 프로젝트, 리전, 존
provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}
