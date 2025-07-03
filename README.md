# Google Doc Extractor
Extract Contents and Style from Google Documents

## Setup

### AWS Credentials
To use S3 upload functionality, create a `.env.local` file in the project root with your AWS credentials:

```
AWS_ACCESS_KEY_ID=your-access-key-here
AWS_SECRET_ACCESS_KEY=your-secret-key-here
AWS_REGION=us-east-1
S3_BUCKET_NAME=your-bucket-name-here
```

The application will first check for credentials in `.env.local`, then fall back to environment variables if the file doesn't exist.
