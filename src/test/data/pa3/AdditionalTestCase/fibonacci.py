def f(x:int) -> int:
    if x == 1 or x == 2:
        return 1
    return f(x-1)+f(x-2)
print(f(10))