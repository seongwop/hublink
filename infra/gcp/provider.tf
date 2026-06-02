
# Terraform이 사용할 GCP 프로젝트, 리전, 존 설정

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}
