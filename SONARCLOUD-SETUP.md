# SonarCloud Setup Guide

## Prerequisites
- Your GitHub repository must be public (which it is: shiftrightlabs/sni-jfr-events)
- You need admin access to the repository (you have this)

## Setup Steps

### 1. Sign Up for SonarCloud (Free for Open Source)

1. Go to https://sonarcloud.io/
2. Click "Log in" → "With GitHub"
3. Authorize SonarCloud to access your GitHub account
4. Select **Free Plan** (for public repositories)

### 2. Import Your Repository

1. After logging in, click the "+" icon → "Analyze new project"
2. Select your organization: `shiftrightlabs`
3. Select repository: `sni-jfr-events`
4. Click "Set Up"

### 3. Configure SonarCloud

1. Choose "With GitHub Actions" as the analysis method
2. SonarCloud will show you:
   - **Organization Key**: `shiftrightlabs` (should match what's in the workflow)
   - **Project Key**: `shiftrightlabs_sni-jfr-events` (should match what's in the workflow)

### 4. Add SONAR_TOKEN to GitHub Secrets

1. In SonarCloud, go to your project → Administration → Security
2. Generate a new token:
   - Name: `GitHub Actions`
   - Type: `User Token` or `Project Token`
   - Copy the generated token

3. In GitHub, go to repository settings:
   - https://github.com/shiftrightlabs/sni-jfr-events/settings/secrets/actions
   - Click "New repository secret"
   - Name: `SONAR_TOKEN`
   - Value: [paste the token from SonarCloud]
   - Click "Add secret"

### 5. Verify the Configuration

The workflow file `.github/workflows/maven-build.yml` has been updated with:
- Organization: `shiftrightlabs`
- Project Key: `shiftrightlabs_sni-jfr-events`

If your organization key or project key is different:
1. Update the `sonar.organization` and `sonar.projectKey` parameters in the workflow file
2. The values are in line 138 of `maven-build.yml`

### 6. Test the Setup

1. Commit the workflow changes:
   ```bash
   git add .github/workflows/maven-build.yml
   git commit -m "ci: Add SonarCloud analysis to CI pipeline"
   git push
   ```

2. Check the Actions tab to see if SonarCloud analysis runs successfully
3. Visit https://sonarcloud.io/project/overview?id=shiftrightlabs_sni-jfr-events to see results

### 7. Update Branch Protection (After Testing)

Once SonarCloud is working, run:
```bash
gh api repos/shiftrightlabs/sni-jfr-events/branches/master/protection \
  --method PUT \
  --input - << 'EOF'
{
  "required_status_checks": {
    "strict": true,
    "checks": [
      {"context": "Build with Java 11"},
      {"context": "Build with Java 17"},
      {"context": "Build with Java 21"},
      {"context": "Code Quality Check"},
      {"context": "Dependency Security Check"},
      {"context": "SonarCloud Analysis"}
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": false,
    "require_code_owner_reviews": false
  },
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": false,
  "lock_branch": false,
  "allow_fork_syncing": false
}
EOF
```

## What SonarCloud Analyzes

- **Code Quality**: Code smells, maintainability issues
- **Security**: Vulnerabilities, security hotspots
- **Reliability**: Bugs and potential runtime issues
- **Coverage**: Test coverage (if configured)
- **Duplications**: Duplicate code detection

## Features (All Free for Public Projects)

✅ Unlimited lines of code
✅ Pull request decoration
✅ Branch analysis
✅ Quality gate
✅ Code coverage
✅ Security analysis
✅ Public project dashboard at https://sonarcloud.io/

## Troubleshooting

### If the analysis fails:
1. Check that `SONAR_TOKEN` is correctly set in GitHub Secrets
2. Verify organization and project keys match SonarCloud
3. Check the Actions logs for specific error messages

### If the project key is wrong:
- Update line 138 in `.github/workflows/maven-build.yml` with the correct keys from SonarCloud

### To see your SonarCloud dashboard:
- https://sonarcloud.io/project/overview?id=shiftrightlabs_sni-jfr-events
