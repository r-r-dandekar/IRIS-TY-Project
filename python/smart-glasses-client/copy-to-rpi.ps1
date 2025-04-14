param(
    [Parameter(Mandatory=$true)]
    [string]$RemoteHost
)

# Define source, destination, remote user, and the directory to exclude
$remoteUser = "radhan"
$remoteSourceDir = "/home/radhan/iris-client-rpi"
$localDestinationDir = "D:/Radhan-Data/Repos-And-Projects/IRIS-TY-Project/python/smart-glasses-client"
$excludeDirName = "venv3.11.2"

# Construct the full remote source path
$fullRemoteSource = "$remoteUser@${RemoteHost}:$remoteSourceDir"

# Construct the full local destination path
$fullLocalDestination = "$localDestinationDir"

# Get all items (files and directories) within the remote source directory
$remoteItemsOutput = ssh "$remoteUser@${RemoteHost}" "find '$remoteSourceDir' -print0" | Out-String
$remoteItems = $remoteItemsOutput.Split("`0", [System.StringSplitOptions]::RemoveEmptyEntries)

# Filter out the unwanted directory
$itemsToCopy = $remoteItems | Where-Object {$_ -notlike "*$excludeDirName*"}

# Create the local destination directory if it doesn't exist
if (-not (Test-Path $fullLocalDestination -PathType Container)) {
    Write-Host "Creating local directory: $fullLocalDestination"
    New-Item -Path $fullLocalDestination -ItemType Directory -Force | Out-Null
}

# Loop through the filtered items and copy them individually
foreach ($item in $itemsToCopy) {
    # Construct the relative path from the base remote directory
    $relativePath = $item.Substring($remoteSourceDir.Length).TrimStart('/')

    # Construct the local destination path for the current item
    $localPath = Join-Path $fullLocalDestination $relativePath

    # Determine if the item is a directory or a file
    $isDirectory = ssh "$remoteUser@${RemoteHost}" "test -d '$item' && echo 'True' || echo 'False'"

    if ($isDirectory -eq "True") {
        # Create the local subdirectory if it doesn't exist
        if (-not (Test-Path $localPath -PathType Container)) {
            Write-Host "Creating local directory: $localPath"
            New-Item -Path $localPath -ItemType Directory -Force | Out-Null
        }
    } else {
        # Construct the scp command to copy the file
        $scpCommand = "scp '$remoteUser@${RemoteHost}:$item' '$localPath'"
        Write-Host "Copying: $relativePath"
        Invoke-Expression $scpCommand
    }
}

Write-Host "Copy process completed, excluding the '$excludeDirName' directory from '$RemoteHost'."