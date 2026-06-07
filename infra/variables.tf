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
