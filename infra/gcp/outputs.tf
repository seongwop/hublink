
# apply 이후 VM IP, SSH 명령어, .env.gcp에 넣을 값 출력

output "artifact_registry_url" {
  description = "HubLink 이미지를 저장할 Docker Registry URL"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
}

output "vm_internal_ips" {
  description = "HubLink VM의 고정 내부 IP 목록"
  value = {
    for name, vm in google_compute_instance.vm :
    name => vm.network_interface[0].network_ip
  }
}

output "vm_external_ips" {
  description = "HubLink VM에 할당된 임시 외부 IP 목록"
  value = {
    for name, vm in google_compute_instance.vm :
    name => vm.network_interface[0].access_config[0].nat_ip
  }
}

output "ssh_commands" {
  description = "각 VM에 접속하기 위한 gcloud SSH 명령어"
  value = {
    for name, vm in google_compute_instance.vm :
    name => "gcloud compute ssh ${vm.name} --zone ${var.zone} --project ${var.project_id}"
  }
}

output "env_gcp_values" {
  description = ".env.gcp에 복사할 주요 값"
  value = {
    IMAGE_REGISTRY = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
    PLATFORM_VM_IP = google_compute_instance.vm["platform"].network_interface[0].network_ip
    DOMAIN_A_VM_IP = google_compute_instance.vm["domain-a"].network_interface[0].network_ip
    DOMAIN_B_VM_IP = google_compute_instance.vm["domain-b"].network_interface[0].network_ip
    DATA_VM_IP     = google_compute_instance.vm["data-monitor"].network_interface[0].network_ip
  }
}
