watch -n 1 "\
    true \
    && echo "   lb:" && docker exec -it lb cat /proc/net/tcp /proc/net/tcp6 | cut -d' ' -f5 | grep 270F | wc -l \
    && echo "api01:" && docker exec -it api01 cat /proc/net/tcp /proc/net/tcp6 | cut -d' ' -f5 | grep 2706 | wc -l \
    && echo "api02:" && docker exec -it api02 cat /proc/net/tcp /proc/net/tcp6 | cut -d' ' -f5 | grep 2706 | wc -l \
    && echo "db:" && docker exec -it db cat /proc/net/tcp /proc/net/tcp6  | wc -l \
    && PGPASSWORD=123 psql -h localhost -U rinha -d rinha -p 5400 -c \
        'SELECT COUNT(*) FROM pg_stat_activity;' \
    "
