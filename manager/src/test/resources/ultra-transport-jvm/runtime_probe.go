package main

import (
    "encoding/base64"
    "fmt"
    "io"
    "os"
    "path/filepath"
    "strings"
    "time"
)

func main() {
    input, _ := io.ReadAll(os.Stdin)
    args := os.Args[1:]
    executable, _ := os.Executable()
    if trace, err := os.OpenFile(filepath.Join(filepath.Dir(executable), "invocations.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600); err == nil {
        fmt.Fprintln(trace, args[0])
        trace.Close()
    }
    if len(args) >= 5 && args[2] == "/system/bin/sh" && args[3] == "-c" {
        if args[4] == "/system/bin/id -u" { fmt.Println("0"); return }
        if args[4] == "fixture-exit-23" { os.Exit(23) }
        if args[4] == "fixture-interrupted-wait" {
            os.WriteFile(filepath.Join(filepath.Dir(executable), "wait.pid"), []byte(fmt.Sprint(os.Getpid())), 0600)
            os.Stdout.Close()
            os.Stderr.Close()
            time.Sleep(30 * time.Second)
            return
        }
    }
    fmt.Printf("MODE:%s\nINPUT:%s\nARGS:%s\n", args[0], base64.StdEncoding.EncodeToString(input), strings.Join(args[2:], "|"))
    if os.Getenv("FIXTURE_DENIED") == "1" { os.Exit(69) }
}
