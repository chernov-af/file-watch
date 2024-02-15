# Утилита для иллюстрации слежения за изменениями файлов

События, за которыми нужно следить, передаются в конструктор класса `FileChangePublisher`.<br/>
Следить можно за следующими событиями:
 - создание файла
 - изменение файла
 - удаление файла

Утилита умеет следить и за настоящими файлами, и за symlink-ами, в том числе за файлами,
монтированными в контейнер из kind: Secret или kind: ConfigMap в k8s.<br/>
В случае с файлами в k8s нужно следить за событиями удаления файлов.

## Запуск утилиты без предварительной компилляции

Используется возможность Java 11 "[Launch Single-File Source-Code Programs](https://stackoverflow.com/questions/54493058/running-a-java-program-without-compiling/54493093)".

Для запуска утилиты выполните команду:
```shell
java FilesWatcher.java "/path/to/file1.txt" "/path/to/file2.txt" ...
```

## Запуск скомпилированной утилиты

Скомпилируйте проект и перейдите в каталог `<project-dir>/target/file-watch/classes`.

Для запуска утилиты выполните команду:
```shell
java ru.chernovaf.filewatch.FilesWatcher "/path/to/file1.txt" "/path/to/file2.txt" ...
```
