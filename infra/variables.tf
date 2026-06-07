variable "aws_region" {
  description = "AWS region for regional resources."
  type        = string
  default     = "eu-central-1"
}

variable "project_name" {
  description = "Short project name used in resource names and tags."
  type        = string
  default     = "web-streaming"
}

variable "environment" {
  description = "Deployment environment name."
  type        = string
  default     = "dev"
}

variable "alert_email" {
  description = "Email address for AWS Budget alerts."
  type        = string
}

variable "monthly_budget_limit_usd" {
  description = "Monthly budget limit in USD."
  type        = number
  default     = 5
}

variable "github_repository" {
  description = "GitHub repository allowed to assume the deploy role, in owner/repo format."
  type        = string
}

variable "github_branch" {
  description = "GitHub branch allowed to deploy."
  type        = string
  default     = "main"
}

variable "frontend_bucket_name" {
  description = "Optional globally unique S3 bucket name. Leave null to derive one from account and region."
  type        = string
  default     = null
}

variable "cloudfront_price_class" {
  description = "CloudFront price class. PriceClass_100 is the most cost-conscious option."
  type        = string
  default     = "PriceClass_100"
}

variable "instance_type" {
  description = "EC2 instance type for Janus."
  type        = string
  default     = "t3.micro"
}

variable "allowed_ssh_cidrs" {
  description = "CIDR blocks allowed to SSH into the Janus EC2 instance. Leave empty to disable SSH ingress."
  type        = list(string)
  default     = []
}

variable "janus_rtp_port_from" {
  description = "First UDP RTP port opened for Janus media."
  type        = number
  default     = 10000
}

variable "janus_rtp_port_to" {
  description = "Last UDP RTP port opened for Janus media."
  type        = number
  default     = 10200
}

variable "enable_janus_instance" {
  description = "Whether to create the EC2 Janus instance."
  type        = bool
  default     = false
}
