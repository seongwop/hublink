# GCP 환경, VM 스펙, IP, 접근 허용 범위 입력값

# Terraform 리소스 생성 대상 프로젝트 ID
variable "project_id" {
  description = "GCP 프로젝트 ID"
  type        = string
}

# VM 자동 시작/종료 스케줄 사용 여부
variable "vm_schedule_enabled" {
  description = "VM 자동 시작/종료 스케줄 사용 여부"
  type        = bool
  default     = false
}

# VM 자동 시작 cron
variable "vm_start_schedule" {
  description = "VM 자동 시작 cron"
  type        = string
  default     = "30 9 * * *"
}

# VM 자동 종료 cron
variable "vm_stop_schedule" {
  description = "VM 자동 종료 cron"
  type        = string
  default     = "0 3 * * *"
}

# VM 스케줄 타임존
variable "vm_schedule_time_zone" {
  description = "VM 스케줄 타임존"
  type        = string
  default     = "Asia/Seoul"
}

# 전체 리소스 생성 리전
variable "region" {
  description = "GCP 리전"
  type        = string
  default     = "asia-northeast3"
}

# Compute Engine VM 생성 존
variable "zone" {
  description = "Compute Engine VM 생성 존"
  type        = string
  default     = "asia-northeast3-a"
}

# HubLink 리소스 이름 공통 접두어
variable "name_prefix" {
  description = "HubLink GCP 리소스 이름 접두어"
  type        = string
  default     = "hublink"
}

# HubLink VM 서브넷 CIDR 대역
variable "network_cidr" {
  description = "HubLink 서브넷 CIDR 대역"
  type        = string
  default     = "10.10.0.0/24"
}

# 외부 IP 없는 VM의 인터넷 egress용 Cloud NAT 사용 여부
variable "cloud_nat_enabled" {
  description = "외부 IP 없는 VM의 인터넷 egress용 Cloud NAT 사용 여부"
  type        = bool
  default     = false
}

# VM 부팅 이미지 프로젝트
variable "boot_image_project" {
  description = "VM 부팅 이미지 프로젝트"
  type        = string
  default     = "debian-cloud"
}

# VM 부팅 이미지 패밀리
variable "boot_image_family" {
  description = "VM 부팅 이미지 패밀리"
  type        = string
  default     = "debian-12"
}

# SSH 공개키 직접 등록용 Linux 사용자명
variable "ssh_username" {
  description = "SSH 공개키 등록용 Linux 사용자명"
  type        = string
  default     = "hublink"
}

# VM 메타데이터 직접 등록용 SSH 공개키
variable "ssh_public_key" {
  description = "VM 메타데이터에 등록할 SSH 공개키"
  type        = string
  default     = ""
}

# 외부 IP 직접 SSH 허용 CIDR 목록
variable "ssh_source_ranges" {
  description = "SSH 접근 허용 CIDR 목록"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# 공개 확인 포트 접근 허용 CIDR 목록
variable "public_source_ranges" {
  description = "공개 서비스 포트 접근 허용 CIDR 목록"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# 고정 외부 IP 대상 VM 목록
variable "external_ip_vm_names" {
  description = "고정 외부 IP를 연결할 VM 이름 목록"
  type        = set(string)
  default     = ["platform", "data", "monitoring", "load-test"]
}

# Docker 이미지 저장소 ID
variable "artifact_registry_repository_id" {
  description = "Artifact Registry Docker 저장소 ID"
  type        = string
  default     = "hublink"
}

# GitHub Actions OIDC 허용 repository
variable "github_repository" {
  description = "GitHub Actions OIDC 허용 repository"
  type        = string
  default     = "seongwop/hublink"
}

# VM 역할별 스펙과 네트워크 태그
variable "vm_specs" {
  description = "VM 역할별 스펙과 네트워크 설정"
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
    data = {
      role         = "data"
      machine_type = "e2-standard-2"
      disk_size_gb = 50
      internal_ip  = "10.10.0.40"
      tags         = ["hublink", "hublink-ssh", "hublink-data"]
    }
    monitoring = {
      role         = "monitoring"
      machine_type = "e2-standard-2"
      disk_size_gb = 50
      internal_ip  = "10.10.0.60"
      tags         = ["hublink", "hublink-ssh", "hublink-monitoring"]
    }
    load-test = {
      role         = "load-test"
      machine_type = "e2-medium"
      disk_size_gb = 20
      internal_ip  = "10.10.0.50"
      tags         = ["hublink", "hublink-ssh", "hublink-load-test"]
    }
  }
}
