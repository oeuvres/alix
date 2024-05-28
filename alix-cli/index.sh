DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
echo $DIR
java -cp "$DIR/lib/*" alix.cli.Load "$@"
touch $DIR/web.xml # reload webapp
