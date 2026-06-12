# Terraform apply 후 확인할 주요 배포 값

# Docker build/push와 .env.gcp용 Registry 주소
output "artifact_registry_url" {
  description = "HubLink Docker 이미지 저장소 URL"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
}

# VM 간 통신과 compose 환경변수용 내부 IP
output "vm_internal_ips" {
  description = "HubLink VM 고정 내부 IP 목록"
  value = {
    for name, vm in google_compute_instance.vm :
    name => vm.network_interface[0].network_ip
  }
}

# 브라우저 접속과 임시 SSH 확인용 외부 IP
output "vm_external_ips" {
  description = "HubLink VM 고정 외부 IP 목록"
  value = {
    for name, address in google_compute_address.external :
    name => address.address
  }
}

# VM 직접 접속용 gcloud SSH 명령어
output "ssh_commands" {
  description = "VM 접속용 gcloud SSH 명령어"
  value = {
    for name, vm in google_compute_instance.vm :
    name => "gcloud compute ssh ${vm.name} --zone ${var.zone} --project ${var.project_id} --tunnel-through-iap"
  }
}

# .env.gcp와 GitHub Actions 참고값
output "env_gcp_values" {
  description = ".env.gcp에 반영할 주요 값"
  value = {
    IMAGE_REGISTRY  = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
    PLATFORM_VM_IP  = google_compute_instance.vm["platform"].network_interface[0].network_ip
    DOMAIN_A_VM_IP  = google_compute_instance.vm["domain-a"].network_interface[0].network_ip
    DOMAIN_B_VM_IP  = google_compute_instance.vm["domain-b"].network_interface[0].network_ip
    DATA_VM_IP       = try(google_compute_instance.vm["data"].network_interface[0].network_ip, null)
    MONITORING_VM_IP = try(google_compute_instance.vm["monitoring"].network_interface[0].network_ip, null)
    LOAD_TEST_VM_IP = google_compute_instance.vm["load-test"].network_interface[0].network_ip
  }
}
