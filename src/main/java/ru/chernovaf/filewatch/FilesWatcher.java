package ru.chernovaf.filewatch;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Чернов Андрей, AFChernov.SBT@sberbank.ru
 * created 10.01.2024
 */
public class FilesWatcher {

    public static void main( String... args ) throws IOException {
        List<Path> filePaths = getFilePathsFrom( args );
        if ( filePaths == null ) {
            return;
        }
        FileChangeSubscriber subscriber =
                new FileChangeSubscriber( filePaths );
        FileChangePublisher publisher =
                new FileChangePublisher( List.of( subscriber ),
                                         FileSystems.getDefault().newWatchService(),
                                         EnumSet.of( EventKind.FILE_CREATE,
                                                     EventKind.FILE_MODIFY,
                                                     EventKind.FILE_DELETE ) );
        infiniteFileWatchCycle( publisher );
    }

    private static List<Path> getFilePathsFrom( String... args ) {
        if ( args.length < 1 ) {
            log( "[ERROR] Files to watch are not specified!" );
            log( "[ERROR] Need specify absolute file names in command line arguments." );
            return null;
        }
        List<Path> filePaths = toFilePaths( args );
        if ( filePaths == null ) {
            log( "[ERROR] Files to watch are specified wrong!" );
            log( "[ERROR] Need specify ABSOLUTE names of the EXISTING FILES in command line arguments." );
            return null;
        }
        return filePaths;
    }

    private static void log( String format, Object... args ) {
        System.out.printf( format + "%n", args );
    }

    private static List<Path> toFilePaths( String... fileNames ) {
        List<Path> paths = new ArrayList<>( fileNames.length );
        for ( String fileName : fileNames ) {
            Path filePath = Paths.get( fileName );
            if ( !filePath.isAbsolute() ||
                 !Files.exists( filePath ) ||
                 !Files.isRegularFile( filePath ) )
                return null;
            else
                paths.add( filePath );
        }
        return paths;
    }

    private static void infiniteFileWatchCycle( FileChangePublisher publisher ) {
        try {
            log( "" );
            publisher.start();
            log( "The WatchService has started to watch for directories" );
            //noinspection InfiniteLoopStatement
            while ( true ) {
                publisher.takeAndNotifySubscribers();
            }
        } catch (ClosedWatchServiceException e) {
            log( "[ERROR] The WatchService has been stopped forced");
            e.printStackTrace();
        } catch ( InterruptedException e ) {
            log( "[ERROR] The WatchService's thread %s has been interrupted", Thread.currentThread().getName() );
            e.printStackTrace();
        } catch ( Exception e ) {
            log( "[ERROR] The WatchService error has been occurred" );
            e.printStackTrace();
        } finally {
            log( "The WatchService has been stopped" );
            publisher.stop();
        }
    }

    private static class FileChangePublisher {

        private final List<FileChangeSubscriber> subscribers;
        private final WatchService watchService;
        private final EnumSet<EventKind> watchEventKinds;

        private Set<Path> registeredSubscriptionDirs;
        private Map<Path, List<Path>> realPathToSubscriptionPaths;
        private Map<Path, Path> subscriptionPathToRealPath;

        public FileChangePublisher( List<FileChangeSubscriber> subscribers,
                                    WatchService watchService, EnumSet<EventKind> watchEventKinds ) {
            this.subscribers = subscribers;
            this.watchService = watchService;
            this.watchEventKinds = watchEventKinds;
            this.registeredSubscriptionDirs = null;
            this.realPathToSubscriptionPaths = null;
            this.subscriptionPathToRealPath = null;
        }

        public void start() {
            try {
                registerDirsWithWatchService();
            } catch ( IOException e ) {
                throw new RuntimeException( "Cannot register WatchService for one or more directories", e );
            }
        }

        private void registerDirsWithWatchService() throws IOException {
            Set<Path> subscriptionDirsForReg = getSubscriptionDirsForRegister();

            for ( Path subscriptionDir : subscriptionDirsForReg ) {
                registerDirWithWatchService( subscriptionDir );
            }

            registeredSubscriptionDirs = subscriptionDirsForReg;
        }

        private Set<Path> getSubscriptionDirsForRegister() throws IOException {
            realPathToSubscriptionPaths = new HashMap<>();
            subscriptionPathToRealPath = new HashMap<>();
            Set<Path> result = new LinkedHashSet<>();
            for ( FileChangeSubscriber subscriber : subscribers ) {
                for ( Path subscriptionFilePath : subscriber.subscriptions() ) {
                    // Получаем имя реального файла, т.к. subscriptionFilePath может быть symlink-ом
                    Path realFilePath = subscriptionFilePath.toRealPath();
                    putOrAccumulate( realPathToSubscriptionPaths, realFilePath, subscriptionFilePath,
                                     this::createArrayListOf, Collection::add );
                    subscriptionPathToRealPath.put( subscriptionFilePath, realFilePath );

                    Path realParentDirPath = Objects.requireNonNull( realFilePath.getParent() );
                    result.add( realParentDirPath );
                }
            }
            return result;
        }

        private <K, V, E> void putOrAccumulate( Map<K, V> map, K key, E valueElement,
                                                Function<E, V> valueConstructor,
                                                BiConsumer<V, E> valueAccumulator ) {
            V value = map.get( key );
            if ( value == null )
                map.put( key, valueConstructor.apply( valueElement ) );
            else
                valueAccumulator.accept( value, valueElement );
        }

        private List<Path> createArrayListOf( Path element ) {
            List<Path> list = new ArrayList<>();
            list.add( element );
            return list;
        }

        private void registerDirWithWatchService( Path directory ) throws IOException {
            WatchEvent.Kind<?>[] events = watchEventKinds.stream()
                                                         .map( EventKind::getWatchEventKind )
                                                         .toArray( WatchEvent.Kind[]::new );
            directory.register( watchService, events );
            log( "WatchService has been registered for watch the directory %s (eventKinds: %s)",
                 directory, watchEventKinds );
        }

        public void pollAndNotifySubscribers() {
            try {
                WatchKey key = watchService.poll();
                if ( key != null )
                    pollKeyEvents( key );
            } catch ( IOException e ) {
                throw new RuntimeException( "Error of poll WatchKey events", e );
            }
        }

        public void takeAndNotifySubscribers() throws InterruptedException {
            try {
                WatchKey key = watchService.take();
                if ( key != null )
                    pollKeyEvents( key );
            } catch ( IOException e ) {
                throw new RuntimeException( "Error of poll WatchKey events", e );
            }
        }

        private void pollKeyEvents( WatchKey key ) throws IOException {
            try {
                log( "" );
                Path dirPath = ( Path )key.watchable();
                Map<Path, EnumSet<EventKind>> occurredFilesEventKinds = new HashMap<>();
                for ( WatchEvent<?> event : key.pollEvents() ) {
                    Path realFilePath = dirPath.resolve( ( Path )event.context() ).toAbsolutePath();
                    EventKind eventKind = EventKind.from( ( WatchEvent.Kind<Path> )event.kind() );

                    List<Path> subscriptionFilePaths = realPathToSubscriptionPaths.get( realFilePath );
                    if ( subscriptionFilePaths != null )
                        for ( Path subscriptionFilePath : subscriptionFilePaths ) {
                            if ( subscriptionFilePath.equals( realFilePath ) )
                                log( "Event %s has been occurred for the file %s",
                                     eventKind, subscriptionFilePath );
                            else
                                log( "Event %s has been occurred for the file %s -> %s",
                                     eventKind, subscriptionFilePath, realFilePath );
                            putOrAccumulate( occurredFilesEventKinds, subscriptionFilePath, eventKind,
                                             EnumSet::of, Collection::add );
                        }
                }

                if ( !occurredFilesEventKinds.isEmpty() )
                    notifySubscribers( occurredFilesEventKinds );
            } finally {
                // Нужно обязательно вызвать в конце работы с WatchKey, чтобы WatchService мог продолжать работу
                key.reset();
            }
        }

        private void notifySubscribers( Map<Path, EnumSet<EventKind>> occurredFilesEventKinds ) throws IOException {
            for ( FileChangeSubscriber subscriber : subscribers ) {
                List<Path> subscriberEvents = new ArrayList<>();
                for ( Path subscriptionFilePath : subscriber.subscriptions() ) {
                    EnumSet<EventKind> occurredEventKinds = occurredFilesEventKinds.get( subscriptionFilePath );
                    if ( occurredEventKinds != null ) {
                        EnumSet<EventKind> subscriptionEventKinds = EnumSet.copyOf( watchEventKinds );
                        subscriptionEventKinds.retainAll( occurredEventKinds );
                        if ( !subscriptionEventKinds.isEmpty() )
                            subscriberEvents.add( subscriptionFilePath );

                        if ( occurredEventKinds.contains( EventKind.FILE_DELETE ) &&
                             Files.exists( subscriptionFilePath ) )  // k8s like case
                            repeatedRegisterDirIfChanged( subscriptionFilePath );
                    }
                }

                if ( !subscriberEvents.isEmpty() )
                    subscriber.onEvents( subscriberEvents );
            }
        }

        private void repeatedRegisterDirIfChanged( Path subscriptionFilePath ) throws IOException {
            Path realFilePath = subscriptionPathToRealPath.get( subscriptionFilePath );
            Path realParentDirPath = Objects.requireNonNull( realFilePath.getParent() );
            Path newRealFilePath = subscriptionFilePath.toRealPath();
            Path newRealParentDirPath = Objects.requireNonNull( newRealFilePath.getParent() );
            if ( !realParentDirPath.equals( newRealParentDirPath ) ) {
                subscriptionPathToRealPath.put( subscriptionFilePath, newRealFilePath );

                removeValueElement( realPathToSubscriptionPaths, realFilePath, subscriptionFilePath );
                putOrAccumulate( realPathToSubscriptionPaths, newRealFilePath, subscriptionFilePath,
                                 this::createArrayListOf, Collection::add );

                if ( registeredSubscriptionDirs.contains( realParentDirPath ) ) {
                    registerDirWithWatchService( newRealParentDirPath );
                    registeredSubscriptionDirs.remove( realParentDirPath );
                    registeredSubscriptionDirs.add( newRealParentDirPath );
                }
            }
        }

        private <T> void removeValueElement( Map<T, List<T>> map, T key, T valueElement ) {
            List<T> value = map.get( key );
            for ( int i = 0; i < value.size(); ++i ) {
                if ( value.get( i ).equals( valueElement ) ) {
                    value.remove( i );
                    break;
                }
            }
            if ( value.isEmpty() ) {
                map.remove( key );
            }
        }

        public void stop() {
            registeredSubscriptionDirs = null;
            realPathToSubscriptionPaths = null;
            subscriptionPathToRealPath = null;
            try {
                watchService.close();
            } catch ( IOException e ) {
                throw new RuntimeException( "Cannot stop WatchService", e );
            }
        }
    }

    private enum EventKind {

        FILE_CREATE( StandardWatchEventKinds.ENTRY_CREATE ),
        FILE_MODIFY( StandardWatchEventKinds.ENTRY_MODIFY ),
        FILE_DELETE( StandardWatchEventKinds.ENTRY_DELETE );

        private final WatchEvent.Kind<Path> watchEventKind;

        EventKind( WatchEvent.Kind<Path> watchEventKind ) {
            this.watchEventKind = watchEventKind;
        }

        public WatchEvent.Kind<Path> getWatchEventKind() {
            return watchEventKind;
        }

        public static EventKind from( WatchEvent.Kind<Path> watchEventKind ) {
            if ( StandardWatchEventKinds.ENTRY_CREATE.equals( watchEventKind ) )
                return FILE_CREATE;
            if ( StandardWatchEventKinds.ENTRY_MODIFY.equals( watchEventKind ) )
                return FILE_MODIFY;
            if ( StandardWatchEventKinds.ENTRY_DELETE.equals( watchEventKind ) )
                return FILE_DELETE;
            throw new IllegalArgumentException( "Unsupported argument: " + watchEventKind );
        }
    }

    private static class FileChangeSubscriber {

        private final List<Path> subscriptionList;

        public FileChangeSubscriber( List<Path> filePaths ) {
            this.subscriptionList = filePaths.stream()
                                             .map( Path::toAbsolutePath )
                                             .collect( Collectors.toList() );
        }

        /**
         * @return Список файлов, на которые подписан данный subscriber.
         */
        public List<Path> subscriptions() {
            return subscriptionList;
        }

        /**
         * @param fileChangeEvents Фактически произошедшие события об изменении файлов
         *     (это подмножество списка, возвращаемого методом {@link #subscriptions()}).
         */
        public void onEvents( List<Path> fileChangeEvents ) {
            for ( Path fileChangeEvent : fileChangeEvents ) {
                log( "The file %s has been changed", fileChangeEvent );
            }
        }
    }
}
