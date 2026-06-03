# VM 자동 시작과 종료 스케줄

# 실습 시간대에만 VM을 실행하기 위한 인스턴스 스케줄
resource "google_compute_resource_policy" "vm_schedule" {
  name   = "${var.name_prefix}-vm-schedule"
  region = var.region

  instance_schedule_policy {
    vm_start_schedule {
      schedule = var.vm_start_schedule
    }

    vm_stop_schedule {
      schedule = var.vm_stop_schedule
    }

    time_zone = var.vm_schedule_time_zone
  }
}
