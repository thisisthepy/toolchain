"""#25 end-to-end fixture: one `@compiled` function. `compiled` is a builtin name (SPEC L-8), so there is no
import; the module must not bind `compiled` itself, or the gate refuses the mark."""


@compiled
def power(base: int, exp: int) -> int:
    result: int = 1
    for i in range(exp):
        result *= base
    return result


def main() -> None:
    print(power(3, 5))
    print(power(3, 50))     # past 2**63: the compiled function must give CPython's exact int
    print(power(-2, 63))


if __name__ == "__main__":
    main()
