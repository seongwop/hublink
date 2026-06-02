# Terraform 및 Google Provider 버전 고정

# 실행 환경별 provider 버전 차이 방지
terraform {
  required_version = ">= 1.6.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}
