
# GCP 환경, VM 스펙, IP, 접근 허용 범위 같은 입력값 정의

variable "project_id" {
  description = "GCP 프로젝트 ID"
  type        = string
}

variable "region" {
  description = "리전 단위 리소스를 생성할 GCP 리전"
  type        = string
  default     = "asia-northeast3"
}

variable "zone" {
  description = "Compute Engine VM을 생성할 GCP 존"
  type        = string
  default     = "asia-northeast3-a"
}

variable "name_prefix" {
  description = "HubLink GCP 리소스 이름에 붙일 공통 접두사"
  type        = string
  default     = "hublink"
}

variable "network_cidr" {
  description = "HubLink 서브넷에 사용할 CIDR 대역"
  type        = string
  default     = "10.10.0.0/24"
}

variable "boot_image_project" {
  description = "VM 부팅 이미지가 있는 GCE 이미지 프로젝트"
  type        = string
  default     = "debian-cloud"
}

variable "boot_image_family" {
  description = "VM 부팅에 사용할 GCE 이미지 패밀리"
  type        = string
  default     = "debian-12"
}

variable "ssh_username" {
  description = "ssh_public_key를 제공할 때 VM SSH 메타데이터에 등록할 Linux 사용자명"
  type        = string
  default     = "hublink"
}

variable "ssh_public_key" {
  description = "SSH 공개키 내용. gcloud compute ssh 또는 OS Login을 사용할 경우 빈 값"
  type        = string
  default     = ""
}

variable "ssh_source_ranges" {
  description = "SSH 접근을 허용할 원본 CIDR 대역"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "public_source_ranges" {
  description = "외부 공개 서비스 포트 접근을 허용할 원본 CIDR 대역"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "artifact_registry_repository_id" {
  description = "Artifact Registry Docker 저장소 ID"
  type        = string
  default     = "hublink"
}

variable "vm_specs" {
  description = "VM 역할, 스펙, 디스크 크기, 고정 내부 IP 설정"
  type = map(object({
    role         = string
    machine_type = string
    disk_size_gb = number
    internal_ip  = string
    tags         = list(string)
  }))

  default = {
    platform = {
      role         = "platform"
      machine_type = "e2-standard-2"
      disk_size_gb = 30
      internal_ip  = "10.10.0.10"
      tags         = ["hublink", "hublink-ssh", "hublink-platform"]
    }
    domain-a = {
      role         = "domain-a"
      machine_type = "e2-standard-2"
      disk_size_gb = 30
      internal_ip  = "10.10.0.20"
      tags         = ["hublink", "hublink-ssh", "hublink-domain"]
    }
    domain-b = {
      role         = "domain-b"
      machine_type = "e2-standard-2"
      disk_size_gb = 30
      internal_ip  = "10.10.0.30"
      tags         = ["hublink", "hublink-ssh", "hublink-domain"]
    }
    data-monitor = {
      role         = "data-monitor"
      machine_type = "e2-standard-2"
      disk_size_gb = 50
      internal_ip  = "10.10.0.40"
      tags         = ["hublink", "hublink-ssh", "hublink-monitor"]
    }
  }
}
