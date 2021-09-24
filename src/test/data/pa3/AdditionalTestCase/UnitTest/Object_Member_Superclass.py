class A(object):
    x:str = "x in A"
    y:int = 1
class B(A):
    z:str = "z in B"
b:A = None
b = B()
print(b.x)
print(b.y)