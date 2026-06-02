# Docker 이미지를 저장할 Artifact Registry 저장소

# GitHub Actions push와 VM pull 대상 저장소
resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = var.artifact_registry_repository_id
  description   = "HubLink 서비스 Docker 이미지 저장소"
  format        = "DOCKER"

  depends_on = [google_project_service.required]
}
