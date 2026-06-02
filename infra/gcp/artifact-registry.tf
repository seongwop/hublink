
# Docker 이미지를 저장할 Artifact Registry 저장소 생성

resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = var.artifact_registry_repository_id
  description   = "Docker images for HubLink services"
  format        = "DOCKER"

  depends_on = [google_project_service.required]
}
