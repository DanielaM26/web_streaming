# AWS Terraform Infrastructure

Free-tier-first infrastructure for the WebRTC streaming MVP.

## What This Creates

- Monthly AWS budget alert
- Private S3 bucket for `index.html` and `app.js`
- CloudFront distribution with Origin Access Control
- Optional small EC2 instance for Janus, disabled by default
- Security group with only web, optional SSH, and Janus RTP UDP ports
- GitHub Actions OIDC role for deploys without long-lived AWS keys

## First Run

Copy the example variables file:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars`:

- `alert_email`
- `github_repository`, for example `your-user/web_streaming`
- `allowed_ssh_cidrs`, or leave empty and use AWS SSM later
- `enable_janus_instance = true` only when you are ready to run Janus

Then run:

```bash
terraform init
terraform plan
terraform apply
```

## Free Tier Notes

This intentionally avoids NAT Gateway, Load Balancer, ECS, RDS, and CodeBuild/CodePipeline.
CloudFront invalidation is disabled in the GitHub workflow unless you set the GitHub Actions variable `ENABLE_CLOUDFRONT_INVALIDATION=true`.
The EC2 instance is disabled by default because it consumes Free plan credits while running, even when idle.
