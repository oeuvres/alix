<?php
$dic = [];
$fh = fopen('word.csv', 'r');
if ($fh === false) {
    throw new RuntimeException('Cannot open CSV file.');
}

$header = fgetcsv($fh,  escape: ''); // INFLECTED,POS,LEMMA

while (($row = fgetcsv($fh,  escape: '')) !== false) {
    if ($row === [null] || count($row) === 0) {
        continue;
    }

    $key = trim($row[0]);

    if ($key === '') {
        continue;
    }

    $dic[$key] = true;
}
fclose($fh);

// loop on names
$names = [];
foreach (['author.csv', 'commune.csv', 'forename.csv', 'france.csv', 'name.csv', 'place.csv'] as $file) {
    $fh = fopen($file, 'r');
    if ($fh === false) {
        throw new RuntimeException('Cannot open CSV file.');
    }
    $header = fgetcsv($fh,  escape: '');
    while (($row = fgetcsv($fh,  escape: '')) !== false) {
        if ($row === [null] || count($row) === 0) {
            continue;
        }
        $name = trim($row[0]);
        if ($name === '') {
            continue;
        }
        $key = mb_strtolower($name, "UTF-8");
        if (isset($dic[$key])) $names[$name] = true;
    }
    fclose($fh);
}
$names = array_keys($names);
$collator = new Collator('fr_FR');
$collator->setStrength(Collator::TERTIARY);
usort($names, static function (string $a, string $b) use ($collator): int {
    $cmp = $collator->compare($a, $b);

    if ($cmp === false) {
        throw new RuntimeException($collator->getErrorMessage());
    }

    return $cmp;
});


$contents = implode("\n", $names) . "\n";
if (file_put_contents("propn-word.csv", $contents) === false) {
    throw new RuntimeException('Cannot write file.');
}
