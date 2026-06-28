# VM 자동 종료 스케줄
# 자동 시작은 서비스 부분 복구 방지를 위해 수동 처리
resource "google_compute_resource_policy" "vm_schedule" {
  name   = "${var.name_prefix}-vm-schedule"
  region = var.region

  instance_schedule_policy {
    vm_stop_schedule {
      schedule = var.vm_stop_schedule
    }

    time_zone = var.vm_schedule_time_zone
  }

  depends_on = [google_project_service.required]
}
