class A(object):
    x:str = "x in A"
    y:int = 1
a:A = None
a = A()
print(a.x)
print(a.y)
a.x = "xx"
print(a.x)
a.y = 2
print(a.y)