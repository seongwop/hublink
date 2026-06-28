# Docker 이미지를 저장할 Artifact Registry 저장소

# GitHub Actions push와 VM pull 대상 저장소
resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = var.artifact_registry_repository_id
  description   = "HubLink 서비스 Docker 이미지 저장소"
  format        = "DOCKER"

  # 롤백용 최근 이미지 보존
  cleanup_policies {
    id     = "keep-recent-versions"
    action = "KEEP"

    most_recent_versions {
      keep_count = 3
    }
  }

  # 최근 보존 대상 외 14일 초과 이미지 정리
  cleanup_policies {
    id     = "delete-older-than-14d"
    action = "DELETE"

    condition {
      tag_state  = "ANY"
      older_than = "1209600s"
    }
  }

  cleanup_policy_dry_run = false

  depends_on = [google_project_service.required]
}
