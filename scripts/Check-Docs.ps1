[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$rootPrefix = $rootPath.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$failures = [Collections.Generic.List[string]]::new()
$files = @(Get-ChildItem -LiteralPath $rootPath -Recurse -File -Filter '*.md' |
    Where-Object { $_.FullName -notmatch '[/\\](\.git|\.tools|node_modules|\.venv|target|build)[/\\]' })

if ($files.Count -eq 0) {
    throw "No Markdown files found in the supplied root."
}

foreach ($file in $files) {
    $relative = [IO.Path]::GetRelativePath($rootPath, $file.FullName)
    $content = [IO.File]::ReadAllText($file.FullName)
    $lines = $content -split "`n"
    $fenceCharacter = $null
    $fenceLength = 0
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = $lines[$index].TrimEnd("`r")
        $location = "${relative}:$($index + 1)"
        if ($line -match '[ \t]+$') {
            $failures.Add("${location}: trailing whitespace")
        }
        if ($line -match '^(<<<<<<< |=======$|>>>>>>> )') {
            $failures.Add("${location}: possible merge conflict marker")
        }
        if ($line -match '^\s{0,3}(`{3,}|~{3,})(.*)$') {
            $marker = $Matches[1]
            $suffix = $Matches[2]
            if ($null -eq $fenceCharacter) {
                $fenceCharacter = $marker[0]
                $fenceLength = $marker.Length
            } elseif ($marker[0] -eq $fenceCharacter -and $marker.Length -ge $fenceLength -and [string]::IsNullOrWhiteSpace($suffix)) {
                $fenceCharacter = $null
            }
            continue
        }
        if ($null -ne $fenceCharacter) { continue }

        # Deliberately checks simple inline relative file links only. Anchor and
        # external URL availability, reference-style links and full Markdown
        # parsing are outside this lightweight repository check.
        foreach ($match in [regex]::Matches($line, '\[[^\]]*\]\(([^\s)]+)\)')) {
            $target = $match.Groups[1].Value.Trim('<', '>')
            if ($target -match '^[A-Za-z][A-Za-z0-9+.-]*:' -or $target.StartsWith('#')) { continue }
            $pathPart = [Uri]::UnescapeDataString(($target -split '[#?]', 2)[0])
            if ([string]::IsNullOrWhiteSpace($pathPart)) { continue }
            $resolved = [IO.Path]::GetFullPath((Join-Path $file.DirectoryName $pathPart))
            if (-not $resolved.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -and $resolved -ne $rootPath) {
                $failures.Add("${location}: relative link escapes repository: $target")
            } elseif (-not (Test-Path -LiteralPath $resolved)) {
                $failures.Add("${location}: missing relative link target: $target")
            }
        }
    }
    if ($null -ne $fenceCharacter) { $failures.Add("${relative}: unclosed fenced code block") }
    if (-not $content.EndsWith("`n")) { $failures.Add("${relative}: missing final newline") }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output $_ }
    exit 1
}
Write-Output "Documentation checks passed for $($files.Count) Markdown files."
