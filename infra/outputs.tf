output "frontend_bucket_name" {
  description = "S3 bucket used for frontend deployment."
  value       = aws_s3_bucket.frontend.bucket
}

output "cloudfront_distribution_id" {
  description = "CloudFront distribution ID for invalidations."
  value       = aws_cloudfront_distribution.frontend.id
}

output "cloudfront_domain_name" {
  description = "CloudFront domain name for the frontend."
  value       = aws_cloudfront_distribution.frontend.domain_name
}

output "github_actions_role_arn" {
  description = "IAM role ARN for GitHub Actions OIDC deploys."
  value       = aws_iam_role.github_actions_deploy.arn
}

output "janus_public_ip" {
  description = "Public IP of the Janus EC2 instance."
  value       = var.enable_janus_instance ? aws_instance.janus[0].public_ip : null
}

